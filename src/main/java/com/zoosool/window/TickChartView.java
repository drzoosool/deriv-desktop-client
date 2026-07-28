package com.zoosool.window;

import com.zoosool.analyze.Resetable;
import com.zoosool.analyze.TickStatsSink;
import com.zoosool.model.MaPoint;
import com.zoosool.model.TickStatsSnapshot;
import com.zoosool.state.TradeWindowState;
import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Живой график: цена + выбранная MA. Фиксированная плотность по X + независимый
 * масштаб по Y. 2D-камера:
 *  - pxPerTick  — плотность по X (сколько пикселей на тик)
 *  - yZoom      — масштаб по Y (1.0 = видимые тики вписаны в высоту; >1 растянуто)
 *  - yPan       — вертикальный сдвиг (когда Y растянут)
 *  - scrollOffset — сдвиг окна по истории (в тиках от правого края)
 *
 * Управление (тачпад):
 *  - щипок (ZoomEvent)      — ОБЩИЙ зум: pxPerTick и yZoom разом (относительно настроенных)
 *  - гориз. свайп / drag    — пан по истории (X)
 *  - верт. свайп            — пан по цене (Y), когда растянуто
 *  - колесо мыши            — общий зум
 *  - слайдер слева (верт.)  — Y-зум независимо
 *  - слайдер снизу (гориз.) — X-плотность независимо
 *  - двойной клик           — сброс к live + дефолты
 *
 * MA берётся готовой из снапшота, не пересчитывается. Буферы по каждому символу.
 */
public final class TickChartView implements TickStatsSink, Resetable {

    private static final int CAPACITY = 500;
    private static final int[] MA_PERIODS = {16, 20, 50};

    private static final double PAD_TOP = 14, PAD_BOTTOM = 22, PAD_LEFT = 26, PAD_RIGHT = 58;

    private static final double PX_PER_TICK_DEFAULT = 6.0;
    private static final double PX_PER_TICK_MIN = 1.5;
    private static final double PX_PER_TICK_MAX = 40.0;

    private static final double Y_ZOOM_DEFAULT = 1.0;
    private static final double Y_ZOOM_MIN = 0.3;
    private static final double Y_ZOOM_MAX = 20.0;

    // размеры слайдеров
    private static final double SLIDER_THICK = 10.0;   // толщина дорожки
    private static final double SLIDER_KNOB = 14.0;    // размер ручки

    private static final class Ring {
        final double[] prices = new double[CAPACITY];
        final double[][] maValues = new double[MA_PERIODS.length][CAPACITY];
        int size = 0;
        int head = 0;

        synchronized void add(double price, double[] maByIndex) {
            prices[head] = price;
            for (int p = 0; p < MA_PERIODS.length; p++) maValues[p][head] = maByIndex[p];
            head = (head + 1) % CAPACITY;
            if (size < CAPACITY) size++;
        }

        synchronized double[] pricesSnapshot() {
            double[] out = new double[size];
            int start = (head - size) % CAPACITY;
            if (start < 0) start += CAPACITY;
            for (int i = 0; i < size; i++) out[i] = prices[(start + i) % CAPACITY];
            return out;
        }

        synchronized double[] maSnapshot(int periodIndex) {
            double[] out = new double[size];
            int start = (head - size) % CAPACITY;
            if (start < 0) start += CAPACITY;
            for (int i = 0; i < size; i++) out[i] = maValues[periodIndex][(start + i) % CAPACITY];
            return out;
        }
    }

    private final TradeWindowState state;

    private final Pane root = new Pane();
    private final Canvas canvas = new Canvas();

    private final Map<String, Ring> buffers = new ConcurrentHashMap<>();
    private volatile String currentSymbol = null;

    private final AtomicBoolean dirty = new AtomicBoolean(false);

    // ── 2D камера ──────────────────────────────────────────────────────
    private double pxPerTick = PX_PER_TICK_DEFAULT;
    private double yZoom = Y_ZOOM_DEFAULT;
    private double yPan = 0.0;                 // пиксельный сдвиг по вертикали
    private int scrollOffset = 0;             // тиков от правого края
    private boolean followLive = true;

    // drag-состояние
    private enum Drag { NONE, PAN, SLIDER_X, SLIDER_Y }
    private Drag dragMode = Drag.NONE;
    private double lastDragX, lastDragY;
    private double dragAccumX;

    public TickChartView(TradeWindowState state) {
        this.state = Objects.requireNonNull(state, "state");
        buildUi();
        installInput();
        startRenderLoop();

        state.selectedAssetProperty().addListener((obs, oldV, newV) -> {
            currentSymbol = (newV == null) ? null : newV.symbol();
            resetView();
            dirty.set(true);
        });
        state.selectedMaPeriodProperty().addListener((o, a, b) -> dirty.set(true));

        var sel = state.getSelectedAsset();
        this.currentSymbol = (sel == null) ? null : sel.symbol();
    }

    public Node getNode() { return root; }

    private void buildUi() {
        root.setStyle("""
                -fx-background-color: #ffffff;
                -fx-background-radius: 16;
                -fx-border-radius: 16;
                -fx-border-color: rgba(0,0,0,0.15);
                """);
        root.setMinSize(200, 150);
        root.setPrefSize(400, 260);
        root.getChildren().add(canvas);

        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> dirty.set(true));
        canvas.heightProperty().addListener((o, a, b) -> dirty.set(true));
    }

    // ── геометрия слайдеров (в координатах канваса) ────────────────────
    // Y-слайдер: вертикальная дорожка слева, в зоне PAD_LEFT.
    // X-слайдер: горизонтальная дорожка снизу, в зоне PAD_BOTTOM.
    private double[] ySliderTrack() { // {x, yTop, yBottom}
        double h = canvas.getHeight();
        double x = PAD_LEFT / 2.0;
        double yTop = PAD_TOP + 4;
        double yBottom = h - PAD_BOTTOM - 4;
        return new double[]{x, yTop, yBottom};
    }
    private double[] xSliderTrack() { // {xLeft, xRight, y}
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        double xLeft = PAD_LEFT + 4;
        double xRight = w - PAD_RIGHT - 4;
        double y = h - PAD_BOTTOM / 2.0;
        return new double[]{xLeft, xRight, y};
    }

    private double yZoomToKnob() { // позиция ручки Y-слайдера (0..1, снизу вверх)
        double t = (Math.log(yZoom) - Math.log(Y_ZOOM_MIN)) / (Math.log(Y_ZOOM_MAX) - Math.log(Y_ZOOM_MIN));
        return clamp(t, 0, 1);
    }
    private double knobToYZoom(double t) {
        double v = Math.exp(Math.log(Y_ZOOM_MIN) + clamp(t, 0, 1) * (Math.log(Y_ZOOM_MAX) - Math.log(Y_ZOOM_MIN)));
        return clamp(v, Y_ZOOM_MIN, Y_ZOOM_MAX);
    }
    private double pxToKnob() {
        double t = (Math.log(pxPerTick) - Math.log(PX_PER_TICK_MIN)) / (Math.log(PX_PER_TICK_MAX) - Math.log(PX_PER_TICK_MIN));
        return clamp(t, 0, 1);
    }
    private double knobToPx(double t) {
        double v = Math.exp(Math.log(PX_PER_TICK_MIN) + clamp(t, 0, 1) * (Math.log(PX_PER_TICK_MAX) - Math.log(PX_PER_TICK_MIN)));
        return clamp(v, PX_PER_TICK_MIN, PX_PER_TICK_MAX);
    }

    private boolean hitYSlider(double px, double py) {
        double[] t = ySliderTrack();
        return Math.abs(px - t[0]) <= SLIDER_KNOB && py >= t[1] - SLIDER_KNOB && py <= t[2] + SLIDER_KNOB;
    }
    private boolean hitXSlider(double px, double py) {
        double[] t = xSliderTrack();
        return Math.abs(py - t[2]) <= SLIDER_KNOB && px >= t[0] - SLIDER_KNOB && px <= t[1] + SLIDER_KNOB;
    }

    private void installInput() {
        // щипок — ОБЩИЙ зум обеих осей
        canvas.setOnZoom(e -> {
            double f = e.getZoomFactor();
            pxPerTick = clamp(pxPerTick * f, PX_PER_TICK_MIN, PX_PER_TICK_MAX);
            yZoom = clamp(yZoom * f, Y_ZOOM_MIN, Y_ZOOM_MAX);
            followLive = false;
            dirty.set(true);
            e.consume();
        });

        canvas.setOnScroll(e -> {
            if (e.isInertia()) return;
            boolean touch = e.getTouchCount() > 0;
            if (touch) {
                // горизонталь — пан по истории; вертикаль — пан по цене
                panByPixelsX(e.getDeltaX());
                yPan += e.getDeltaY();
                followLive = (scrollOffset <= 0) && followLive;
            } else {
                // колесо — общий зум
                double f = e.getDeltaY() > 0 ? 1.1 : (1.0 / 1.1);
                pxPerTick = clamp(pxPerTick * f, PX_PER_TICK_MIN, PX_PER_TICK_MAX);
                yZoom = clamp(yZoom * f, Y_ZOOM_MIN, Y_ZOOM_MAX);
                followLive = false;
            }
            dirty.set(true);
            e.consume();
        });

        canvas.setOnMousePressed(e -> {
            double px = e.getX(), py = e.getY();
            if (hitYSlider(px, py)) {
                dragMode = Drag.SLIDER_Y;
                applyYSlider(py);
            } else if (hitXSlider(px, py)) {
                dragMode = Drag.SLIDER_X;
                applyXSlider(px);
            } else {
                dragMode = Drag.PAN;
                lastDragX = px;
                lastDragY = py;
                dragAccumX = 0;
            }
            dirty.set(true);
        });

        canvas.setOnMouseDragged(e -> {
            double px = e.getX(), py = e.getY();
            switch (dragMode) {
                case SLIDER_Y -> applyYSlider(py);
                case SLIDER_X -> applyXSlider(px);
                case PAN -> {
                    panByPixelsX(px - lastDragX);
                    yPan += py - lastDragY;
                    lastDragX = px;
                    lastDragY = py;
                }
                default -> { }
            }
            dirty.set(true);
        });

        canvas.setOnMouseReleased(e -> dragMode = Drag.NONE);

        canvas.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                resetView();
                dirty.set(true);
            }
        });
    }

    private void applyYSlider(double py) {
        double[] t = ySliderTrack();
        double frac = 1.0 - (py - t[1]) / (t[2] - t[1]); // сверху 1, снизу 0
        yZoom = knobToYZoom(frac);
        followLive = false;
    }
    private void applyXSlider(double px) {
        double[] t = xSliderTrack();
        double frac = (px - t[0]) / (t[1] - t[0]);
        pxPerTick = knobToPx(frac);
        followLive = false;
    }

    private void panByPixelsX(double dxPixels) {
        followLive = false;
        dragAccumX += dxPixels / pxPerTick;
        int whole = (int) dragAccumX;
        if (whole != 0) {
            scrollOffset += whole;
            dragAccumX -= whole;
        }
        if (scrollOffset <= 0) {
            scrollOffset = 0;
            followLive = true;
        }
    }

    private void resetView() {
        pxPerTick = PX_PER_TICK_DEFAULT;
        yZoom = Y_ZOOM_DEFAULT;
        yPan = 0.0;
        scrollOffset = 0;
        followLive = true;
        dragAccumX = 0;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void onSnapshot(TickStatsSnapshot s) {
        if (s == null) return;
        String sym = s.symbol();
        if (sym == null) return;

        Double q = s.lastQuote();
        if (q == null || q.isNaN() || q.isInfinite()) return;

        Map<Integer, MaPoint> mas = s.movingAverages();
        double[] maByIndex = new double[MA_PERIODS.length];
        for (int p = 0; p < MA_PERIODS.length; p++) {
            MaPoint mp = (mas == null) ? null : mas.get(MA_PERIODS[p]);
            maByIndex[p] = (mp == null) ? Double.NaN : mp.value();
        }

        buffers.computeIfAbsent(sym, k -> new Ring()).add(q, maByIndex);

        if (sym.equals(currentSymbol) && canvas.getWidth() > 0) dirty.set(true);
    }

    private void startRenderLoop() {
        new AnimationTimer() {
            @Override public void handle(long now) {
                if (dirty.compareAndSet(true, false)) draw();
            }
        }.start();
    }

    private int selectedPeriodIndex() {
        int sel = state.getSelectedMaPeriod();
        for (int p = 0; p < MA_PERIODS.length; p++) if (MA_PERIODS[p] == sel) return p;
        return -1;
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) return;

        String sym = currentSymbol;
        Ring ring = (sym == null) ? null : buffers.get(sym);
        if (ring == null) { drawSliders(g); return; }

        double[] price = ring.pricesSnapshot();
        int n = price.length;
        if (n < 2) { drawSliders(g); return; }

        double plotW = w - PAD_LEFT - PAD_RIGHT;
        double plotH = h - PAD_TOP - PAD_BOTTOM;
        if (plotW <= 0 || plotH <= 0) return;

        int selPi = selectedPeriodIndex();
        double[] ma = (selPi >= 0) ? ring.maSnapshot(selPi) : null;

        int visibleCount = (int) Math.floor(plotW / pxPerTick) + 1;
        if (visibleCount < 2) visibleCount = 2;

        if (followLive) scrollOffset = 0;
        int maxScroll = Math.max(0, n - visibleCount);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        int to = n - scrollOffset;
        int from = Math.max(0, to - visibleCount);
        int m = to - from;
        if (m < 2) { drawSliders(g); return; }

        // Y: база — видимые тики вписаны в высоту при yZoom=1, дальше растягиваем
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            double v = price[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        if (range <= 0) range = 1;
        double mid = (min + max) / 2.0;

        double rightAlign = plotW - (m - 1) * pxPerTick;
        double baseX = PAD_LEFT + Math.max(0, rightAlign);

        // функция цены -> экранный Y с учётом yZoom и yPan
        // при yZoom=1 диапазон [min..max] занимает всю высоту плота (центр = mid).
        double plotCY = PAD_TOP + plotH / 2.0;
        // пикселей на единицу цены при yZoom=1: plotH/range; c зумом умножаем
        double pxPerPrice = (plotH / range) * yZoom;

        // ── линия цены ────────────────────────────────────────────────
        g.setStroke(Color.web("#111111"));
        g.setLineWidth(1.4);
        g.beginPath();
        for (int i = from; i < to; i++) {
            double x = baseX + (i - from) * pxPerTick;
            double y = plotCY - (price[i] - mid) * pxPerPrice + yPan;
            if (i == from) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();

        // ── MA ────────────────────────────────────────────────────────
        if (ma != null) {
            g.setStroke(Color.web("#dc2626"));
            g.setLineWidth(1.2);
            g.beginPath();
            boolean started = false;
            for (int i = from; i < to; i++) {
                double v = ma[i];
                if (Double.isNaN(v)) { started = false; continue; }
                double x = baseX + (i - from) * pxPerTick;
                double y = plotCY - (v - mid) * pxPerPrice + yPan;
                if (!started) { g.moveTo(x, y); started = true; }
                else g.lineTo(x, y);
            }
            g.stroke();
        }

        // ── метка последней цены ──────────────────────────────────────
        double last = price[to - 1];
        double yLast = plotCY - (last - mid) * pxPerPrice + yPan;
        g.setStroke(Color.web("#16a34a", 0.55));
        g.setLineWidth(1.0);
        g.strokeLine(PAD_LEFT, yLast, PAD_LEFT + plotW, yLast);
        g.setFill(Color.web("#15803d"));
        g.fillText(String.format(java.util.Locale.US, "%.4f", last), PAD_LEFT + plotW + 4, yLast + 4);

        // ── подпись ────────────────────────────────────────────────────
        int selPeriod = state.getSelectedMaPeriod();
        String info = (sym == null ? "" : sym) + "  " + n + "t  MA" + selPeriod
                + "  x" + String.format(java.util.Locale.US, "%.1f", pxPerTick)
                + "  y" + String.format(java.util.Locale.US, "%.1f", yZoom)
                + (followLive ? "  LIVE" : "  -" + scrollOffset);
        g.setFill(Color.web("#334155"));
        g.fillText(info, PAD_LEFT + 2, PAD_TOP);

        drawSliders(g);
    }

    private void drawSliders(GraphicsContext g) {
        // Y-слайдер (вертикальный, слева)
        double[] ys = ySliderTrack();
        g.setStroke(Color.web("#cbd5e1"));
        g.setLineWidth(SLIDER_THICK);
        g.strokeLine(ys[0], ys[1], ys[0], ys[2]);
        double yk = ys[2] - yZoomToKnob() * (ys[2] - ys[1]);
        g.setFill(Color.web("#2563eb"));
        g.fillOval(ys[0] - SLIDER_KNOB / 2, yk - SLIDER_KNOB / 2, SLIDER_KNOB, SLIDER_KNOB);

        // X-слайдер (горизонтальный, снизу)
        double[] xs = xSliderTrack();
        g.setStroke(Color.web("#cbd5e1"));
        g.setLineWidth(SLIDER_THICK);
        g.strokeLine(xs[0], xs[2], xs[1], xs[2]);
        double xk = xs[0] + pxToKnob() * (xs[1] - xs[0]);
        g.setFill(Color.web("#2563eb"));
        g.fillOval(xk - SLIDER_KNOB / 2, xs[2] - SLIDER_KNOB / 2, SLIDER_KNOB, SLIDER_KNOB);
    }

    @Override
    public void reset() {
        buffers.clear();
        dirty.set(true);
    }
}