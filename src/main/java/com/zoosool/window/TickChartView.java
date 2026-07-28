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
 * Живой график: цена + выбранная MA, ФИКСИРОВАННАЯ ПЛОТНОСТЬ.
 *
 * Один тик = pxPerTick пикселей. Показываются только последние тики, что влезают
 * в ширину; старые уезжают влево за кадр. Пока тиков мало — график занимает левую
 * часть и дорастает вправо; когда больше — «лента едет».
 *
 * Зум (щипок/колесо) — меняет pxPerTick (тики шире/уже, видно меньше/больше).
 * Пан по X (свайп/drag) — двигает окно в прошлое (в пределах буфера 500).
 * Y — автомасштаб под видимые тики. Двойной клик — назад к «сейчас».
 *
 * MA-значения берутся готовыми из снапшота анализатора, не пересчитываются.
 * Буферы по каждому символу — при переключении картинка видна сразу.
 */
public final class TickChartView implements TickStatsSink, Resetable {

    private static final int CAPACITY = 500;

    private static final int[] MA_PERIODS = {16, 20, 50};

    private static final double PAD_TOP = 14, PAD_BOTTOM = 14, PAD_LEFT = 6, PAD_RIGHT = 58;

    // плотность
    private static final double PX_PER_TICK_DEFAULT = 6.0;
    private static final double PX_PER_TICK_MIN = 1.5;   // максимально «сжато» (много тиков видно)
    private static final double PX_PER_TICK_MAX = 40.0;  // максимально «крупно» (мало тиков)

    /** Кольцевой буфер на один символ: цена + MA по периодам на каждый тик. */
    private static final class Ring {
        final double[] prices = new double[CAPACITY];
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

    // ── камера с фиксированной плотностью ──────────────────────────────
    private double pxPerTick = PX_PER_TICK_DEFAULT;
    // scrollOffset — сколько тиков от правого края мы «отмотали» назад.
    // 0 = смотрим на самый свежий тик у правого края; >0 = ушли в историю.
    private int scrollOffset = 0;
    private boolean followLive = true;   // прилипание к правому краю (свежие тики)

    private double lastDragX;
    private double dragAccumX;            // накопитель дробного пан-сдвига в тиках

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
        // щипок — меняем плотность вокруг курсора (по X)
        canvas.setOnZoom(e -> {
            double f = e.getZoomFactor();
            changeDensity(f);
            followLive = false;
            dirty.set(true);
            e.consume();
        });

        canvas.setOnScroll(e -> {
            if (e.isInertia()) return;
            boolean touch = e.getTouchCount() > 0;
            if (touch) {
                // двупальцевый свайп по X — пан по истории
                panByPixels(e.getDeltaX());
            } else {
                // колесо — зум плотности
                double f = e.getDeltaY() > 0 ? 1.1 : (1.0 / 1.1);
                changeDensity(f);
                followLive = false;
            }
            dirty.set(true);
            e.consume();
        });

        canvas.setOnMousePressed(e -> {
            lastDragX = e.getX();
            dragAccumX = 0;
        });
        canvas.setOnMouseDragged(e -> {
            panByPixels(e.getX() - lastDragX);
            lastDragX = e.getX();
            dirty.set(true);
        });

        canvas.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                resetView();
                dirty.set(true);
            }
        });
    }

    /** Зум плотности: тики шире/уже. f>1 — крупнее (меньше тиков), f<1 — мельче. */
    private void changeDensity(double f) {
        pxPerTick = clamp(pxPerTick * f, PX_PER_TICK_MIN, PX_PER_TICK_MAX);
    }

    /** Пан: сдвиг мышью/свайпом в пикселях -> в тики истории. Вправо = к свежим. */
    private void panByPixels(double dxPixels) {
        followLive = false;
        dragAccumX += dxPixels / pxPerTick;   // сколько тиков сдвинули (дробно копим)
        int whole = (int) dragAccumX;
        if (whole != 0) {
            // тянем вправо (dx>0) -> уменьшаем offset (к свежим); влево -> в историю
            scrollOffset -= whole;
            dragAccumX -= whole;
        }
        clampScroll();
        // если вернулись к правому краю — снова прилипаем к live
        if (scrollOffset <= 0) {
            scrollOffset = 0;
            followLive = true;
        }
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        // верхнюю границу clamp'ит draw() по фактическому size (там знаем n)
    }

    private void resetView() {
        pxPerTick = PX_PER_TICK_DEFAULT;
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
        if (ring == null) return;

        double[] price = ring.pricesSnapshot();
        int n = price.length;
        if (n < 2) return;

        double plotW = w - PAD_LEFT - PAD_RIGHT;
        double plotH = h - PAD_TOP - PAD_BOTTOM;
        if (plotW <= 0 || plotH <= 0) return;

        int selPi = selectedPeriodIndex();
        double[] ma = (selPi >= 0) ? ring.maSnapshot(selPi) : null;

        // ── определяем видимое окно [from, to) по индексам буфера ─────────
        int visibleCount = (int) Math.floor(plotW / pxPerTick) + 1;
        if (visibleCount < 2) visibleCount = 2;

        // следуем за live -> правый край = последний тик; иначе учитываем scrollOffset
        if (followLive) scrollOffset = 0;

        // clamp scrollOffset по фактическому n
        int maxScroll = Math.max(0, n - visibleCount);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        // to = индекс за последним видимым тиком (правый край)
        int to = n - scrollOffset;                 // exclusive
        int from = Math.max(0, to - visibleCount);  // inclusive
        int m = to - from;                          // сколько тиков видно
        if (m < 2) return;

        // ── Y-масштаб под ВИДИМЫЕ тики (цена + видимая MA) ────────────────
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            double v = price[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        if (range <= 0) range = 1;

        // X: тик i рисуется в PAD_LEFT + (i - from) * pxPerTick, прижатый к правому краю.
        // Чтобы последний видимый тик был у правого края плота:
        double rightAlign = plotW - (m - 1) * pxPerTick; // сдвиг, чтобы последний тик = правый край
        double baseX = PAD_LEFT + Math.max(0, rightAlign);

        // ── линия цены (чёрная) ──────────────────────────────────────────
        g.setStroke(Color.web("#111111"));
        g.setLineWidth(1.4);
        g.beginPath();
        for (int i = from; i < to; i++) {
            double x = baseX + (i - from) * pxPerTick;
            double y = PAD_TOP + plotH * (1.0 - (price[i] - min) / range);
            if (i == from) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();

        // ── выбранная MA (красная), разрыв на NaN ────────────────────────
        if (ma != null) {
            g.setStroke(Color.web("#dc2626"));
            g.setLineWidth(1.2);
            g.beginPath();
            boolean started = false;
            for (int i = from; i < to; i++) {
                double v = ma[i];
                if (Double.isNaN(v)) { started = false; continue; }
                double x = baseX + (i - from) * pxPerTick;
                double y = PAD_TOP + plotH * (1.0 - (v - min) / range);
                if (!started) { g.moveTo(x, y); started = true; }
                else g.lineTo(x, y);
            }
            g.stroke();
        }

        // ── метка последней ВИДИМОЙ цены ─────────────────────────────────
        double last = price[to - 1];
        double yLast = PAD_TOP + plotH * (1.0 - (last - min) / range);
        g.setStroke(Color.web("#16a34a", 0.55));
        g.setLineWidth(1.0);
        g.strokeLine(PAD_LEFT, yLast, PAD_LEFT + plotW, yLast);
        g.setFill(Color.web("#15803d"));
        g.fillText(String.format(java.util.Locale.US, "%.4f", last), PAD_LEFT + plotW + 4, yLast + 4);

        // ── подпись ──────────────────────────────────────────────────────
        int selPeriod = state.getSelectedMaPeriod();
        String info = (sym == null ? "" : sym) + "  " + n + " ticks"
                + "  MA" + selPeriod
                + "  px/tick=" + String.format(java.util.Locale.US, "%.1f", pxPerTick)
                + (followLive ? "  [LIVE]" : "  [-" + scrollOffset + "]  (dbl-click = live)");
        g.setFill(Color.web("#334155"));
        g.fillText(info, PAD_LEFT + 2, PAD_TOP);
    }

    @Override
    public void reset() {
        buffers.clear();
        dirty.set(true);
    }
}
