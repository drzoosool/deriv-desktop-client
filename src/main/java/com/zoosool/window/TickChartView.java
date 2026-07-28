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
 * Живой график: цена + выбранная MA.
 *
 * MA-значения НЕ пересчитываются — берутся готовыми из снапшота анализатора
 * (snapshot.movingAverages()). Копим по каждому символу цену + все MA (16/20/50);
 * на отрисовке рисуем выбранную (state.selectedMaPeriod). Переключение MA буфер
 * не трогает — меняется только индекс ряда при рисовании.
 *
 * Буферы по каждому символу — при переключении символа картинка видна сразу.
 *
 * Управление (тачпад):
 *  - щипок (ZoomEvent) — зум обеих осей вокруг курсора
 *  - двупальцевый свайп (ScrollEvent touch) — пан
 *  - колесо мыши — зум
 *  - двойной клик — сброс к «живому» виду
 */
public final class TickChartView implements TickStatsSink, Resetable {

    private static final int CAPACITY = 500;

    // периоды, которые график умеет показывать (должны совпадать с теми, что кладёт анализатор)
    private static final int[] MA_PERIODS = {16, 20, 50};

    /** Кольцевой буфер на один символ: цена + MA по периодам на каждый тик. */
    private static final class Ring {
        final double[] prices = new double[CAPACITY];
        // maValues[periodIndex][slot] — значение MA данного периода (NaN = не прогрета)
        final double[][] maValues = new double[MA_PERIODS.length][CAPACITY];
        int size = 0;
        int head = 0;

        synchronized void add(double price, double[] maByIndex) {
            prices[head] = price;
            for (int p = 0; p < MA_PERIODS.length; p++) {
                maValues[p][head] = maByIndex[p];
            }
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

    // камера
    private boolean live = true;
    private double scaleX = 1.0;
    private double scaleY = 1.0;
    private double offsetX = 0.0;
    private double offsetY = 0.0;
    private double lastDragX, lastDragY;

    public TickChartView(TradeWindowState state) {
        this.state = Objects.requireNonNull(state, "state");
        buildUi();
        installInput();
        startRenderLoop();

        state.selectedAssetProperty().addListener((obs, oldV, newV) -> {
            currentSymbol = (newV == null) ? null : newV.symbol();
            resetCamera();
            dirty.set(true);
        });
        // перерисовать при смене выбранной MA (буфер не трогаем)
        state.selectedMaPeriodProperty().addListener((o, a, b) -> dirty.set(true));

        var sel = state.getSelectedAsset();
        this.currentSymbol = (sel == null) ? null : sel.symbol();
    }

    public Node getNode() {
        return root;
    }

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

    private void installInput() {
        canvas.setOnZoom(e -> {
            double f = e.getZoomFactor();
            zoomAround(e.getX(), e.getY(), f, f);
            live = false;
            dirty.set(true);
            e.consume();
        });

        canvas.setOnScroll(e -> {
            if (e.isInertia()) return;
            boolean touch = e.getTouchCount() > 0;
            if (touch) {
                offsetX += e.getDeltaX();
                offsetY += e.getDeltaY();
                live = false;
            } else {
                double f = e.getDeltaY() > 0 ? 1.1 : (1.0 / 1.1);
                zoomAround(e.getX(), e.getY(), f, f);
                live = false;
            }
            dirty.set(true);
            e.consume();
        });

        canvas.setOnMousePressed(e -> {
            lastDragX = e.getX();
            lastDragY = e.getY();
        });
        canvas.setOnMouseDragged(e -> {
            offsetX += e.getX() - lastDragX;
            offsetY += e.getY() - lastDragY;
            lastDragX = e.getX();
            lastDragY = e.getY();
            live = false;
            dirty.set(true);
        });

        canvas.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                resetCamera();
                dirty.set(true);
            }
        });
    }

    private void zoomAround(double px, double py, double fx, double fy) {
        offsetX = px - (px - offsetX) * fx;
        offsetY = py - (py - offsetY) * fy;
        scaleX = clamp(scaleX * fx, 0.05, 50);
        scaleY = clamp(scaleY * fy, 0.05, 50);
    }

    private void resetCamera() {
        live = true;
        scaleX = 1.0;
        scaleY = 1.0;
        offsetX = 0.0;
        offsetY = 0.0;
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

        // достаём готовые MA из снапшота (НЕ пересчитываем)
        Map<Integer, MaPoint> mas = s.movingAverages();
        double[] maByIndex = new double[MA_PERIODS.length];
        for (int p = 0; p < MA_PERIODS.length; p++) {
            MaPoint mp = (mas == null) ? null : mas.get(MA_PERIODS[p]);
            maByIndex[p] = (mp == null) ? Double.NaN : mp.value();  // NaN = не прогрета
        }

        buffers.computeIfAbsent(sym, k -> new Ring()).add(q, maByIndex);

        if (sym.equals(currentSymbol) && canvas.getWidth() > 0) {
            dirty.set(true);
        }
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
        for (int p = 0; p < MA_PERIODS.length; p++) {
            if (MA_PERIODS[p] == sel) return p;
        }
        return -1; // выбранного периода нет среди известных — MA не рисуем
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) return;

        String sym = currentSymbol;
        Ring ring = (sym == null) ? null : buffers.get(sym);
        if (ring == null) return;

        double[] price = ring.pricesSnapshot();
        int n = price.length;
        if (n < 2) return;

        // масштаб по цене
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : price) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        if (range <= 0) range = 1;

        double padTop = 14, padBottom = 14, padLeft = 6, padRight = 58;
        double plotW = w - padLeft - padRight;
        double plotH = h - padTop - padBottom;
        if (plotW <= 0 || plotH <= 0) return;

        // ── линия цены (чёрная) ──────────────────────────────────────────
        g.setStroke(Color.web("#111111"));
        g.setLineWidth(1.4);
        g.beginPath();
        for (int i = 0; i < n; i++) {
            double bx = padLeft + plotW * (i / (double) (n - 1));
            double by = padTop + plotH * (1.0 - (price[i] - min) / range);
            double x = cameraX(bx);
            double y = cameraY(by);
            if (i == 0) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();

        // ── выбранная MA (красная), разрыв на NaN ────────────────────────
        int pi = selectedPeriodIndex();
        if (pi >= 0) {
            double[] ma = ring.maSnapshot(pi);
            g.setStroke(Color.web("#dc2626"));
            g.setLineWidth(1.2);
            g.beginPath();
            boolean started = false;
            for (int i = 0; i < n; i++) {
                double v = ma[i];
                if (Double.isNaN(v)) { started = false; continue; }  // не прогрета — разрыв
                double bx = padLeft + plotW * (i / (double) (n - 1));
                double by = padTop + plotH * (1.0 - (v - min) / range);
                double x = cameraX(bx);
                double y = cameraY(by);
                if (!started) { g.moveTo(x, y); started = true; }
                else g.lineTo(x, y);
            }
            g.stroke();
        }

        // ── метка последней цены ─────────────────────────────────────────
        double last = price[n - 1];
        double byLast = padTop + plotH * (1.0 - (last - min) / range);
        double yLast = cameraY(byLast);
        g.setStroke(Color.web("#16a34a", 0.55));
        g.setLineWidth(1.0);
        g.strokeLine(padLeft, yLast, padLeft + plotW, yLast);
        g.setFill(Color.web("#15803d"));
        g.fillText(String.format(java.util.Locale.US, "%.4f", last), padLeft + plotW + 4, yLast + 4);

        // ── подпись ──────────────────────────────────────────────────────
        int selPeriod = state.getSelectedMaPeriod();
        g.setFill(Color.web("#334155"));
        g.fillText((sym == null ? "" : sym) + "  " + n + " ticks  MA" + selPeriod
                        + (live ? "" : "  [zoom]  (dbl-click = reset)"),
                padLeft + 2, padTop);
    }

    private double cameraX(double baseX) { return offsetX + baseX * scaleX; }
    private double cameraY(double baseY) { return offsetY + baseY * scaleY; }

    @Override
    public void reset() {
        buffers.clear();
        dirty.set(true);
    }
}