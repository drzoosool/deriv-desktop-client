package com.zoosool.window;

import com.zoosool.analyze.Resetable;
import com.zoosool.analyze.TickStatsSink;
import com.zoosool.model.TickStatsSnapshot;
import com.zoosool.state.TradeWindowState;
import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Живой график цены выбранного символа: последние N тиков, линия.
 * Данные — тот же поток, что кормит метроном (TickStatsSnapshot.lastQuote),
 * поэтому график синхронен со ставками.
 *
 * Управление (тачпад):
 *  - щипок двумя пальцами (ZoomEvent) — зум обеих осей вокруг курсора
 *  - двупальцевый свайп (ScrollEvent) — пан
 *  - колесо мыши — зум (если есть мышь)
 *  - двойной клик — сброс к «живому» виду (автоскролл + автомасштаб)
 *
 * Потоки: onSnapshot из потока тиков — только пишет в буфер.
 * Рисование — FX-поток через AnimationTimer.
 */
public final class TickChartView implements TickStatsSink, Resetable {

    private static final int CAPACITY = 500;

    private final TradeWindowState state;

    private final Pane root = new Pane();
    private final Canvas canvas = new Canvas();

    private final double[] prices = new double[CAPACITY];
    private int size = 0;
    private int head = 0;
    private final Object bufLock = new Object();

    private volatile String currentSymbol = null;

    private final AtomicBoolean dirty = new AtomicBoolean(false);

    // ── камера ────────────────────────────────────────────────────────
    // "живой" режим: следуем за свежими тиками и автомасштаб по видимому.
    // как только пользователь тронул зум/пан — live=false до двойного клика.
    private boolean live = true;
    private double scaleX = 1.0;   // множитель к базовому горизонтальному масштабу
    private double scaleY = 1.0;   // множитель к базовому вертикальному масштабу
    private double offsetX = 0.0;  // сдвиг в пикселях
    private double offsetY = 0.0;

    private double lastDragX, lastDragY;

    public TickChartView(TradeWindowState state) {
        this.state = Objects.requireNonNull(state, "state");
        buildUi();
        installInput();
        startRenderLoop();

        state.selectedAssetProperty().addListener((obs, oldV, newV) -> {
            String sym = (newV == null) ? null : newV.symbol();
            synchronized (bufLock) {
                currentSymbol = sym;
                size = 0;
                head = 0;
            }
            resetCamera();
            dirty.set(true);
        });
        var sel = state.getSelectedAsset();
        this.currentSymbol = (sel == null) ? null : sel.symbol();
    }

    public Node getNode() {
        return root;
    }

    private void buildUi() {
        root.setStyle("""
                -fx-background-color: rgba(255,255,255,0.06);
                -fx-background-radius: 16;
                -fx-border-radius: 16;
                -fx-border-color: rgba(255,255,255,0.10);
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
        // щипок — зум обеих осей вокруг точки жеста
        canvas.setOnZoom(e -> {
            double factor = e.getZoomFactor();
            zoomAround(e.getX(), e.getY(), factor, factor);
            live = false;
            dirty.set(true);
            e.consume();
        });

        // свайп двумя пальцами / колесо
        canvas.setOnScroll(e -> {
            if (e.isInertia()) return;
            boolean touch = e.getTouchCount() > 0;
            if (touch) {
                // двупальцевый свайп — пан
                offsetX += e.getDeltaX();
                offsetY += e.getDeltaY();
                live = false;
            } else {
                // колесо мыши — зум обеих осей
                double factor = e.getDeltaY() > 0 ? 1.1 : (1.0 / 1.1);
                zoomAround(e.getX(), e.getY(), factor, factor);
                live = false;
            }
            dirty.set(true);
            e.consume();
        });

        // drag мышью — пан
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

        // двойной клик — сброс к живому виду
        canvas.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                resetCamera();
                dirty.set(true);
            }
        });
    }

    private void zoomAround(double px, double py, double fx, double fy) {
        // держим точку под курсором на месте при зуме
        offsetX = px - (px - offsetX) * fx;
        offsetY = py - (py - offsetY) * fy;
        scaleX *= fx;
        scaleY *= fy;
        // ограничим, чтобы не улететь
        scaleX = clamp(scaleX, 0.05, 50);
        scaleY = clamp(scaleY, 0.05, 50);
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
        String sym = currentSymbol;
        if (sym == null || !sym.equals(s.symbol())) return;

        Double q = s.lastQuote();
        if (q == null || q.isNaN() || q.isInfinite()) return;

        synchronized (bufLock) {
            prices[head] = q;
            head = (head + 1) % CAPACITY;
            if (size < CAPACITY) size++;
        }
        dirty.set(true);
    }

    private void startRenderLoop() {
        new AnimationTimer() {
            @Override public void handle(long now) {
                if (dirty.compareAndSet(true, false)) draw();
            }
        }.start();
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) return;

        int n;
        double[] snap;
        synchronized (bufLock) {
            n = size;
            snap = new double[n];
            int start = (head - size) % CAPACITY;
            if (start < 0) start += CAPACITY;
            for (int i = 0; i < n; i++) snap[i] = prices[(start + i) % CAPACITY];
        }
        if (n < 2) return;

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (snap[i] < min) min = snap[i];
            if (snap[i] > max) max = snap[i];
        }
        double range = max - min;
        if (range <= 0) range = 1;

        double padTop = 14, padBottom = 14, padLeft = 6, padRight = 52;
        double plotW = w - padLeft - padRight;
        double plotH = h - padTop - padBottom;
        if (plotW <= 0 || plotH <= 0) return;

        // базовые координаты (live), затем применяем камеру
        // x: равномерно по индексам; y: по цене
        g.setStroke(Color.web("#e2e8f0"));
        g.setLineWidth(1.4);
        g.beginPath();
        for (int i = 0; i < n; i++) {
            double bx = padLeft + plotW * (i / (double) (n - 1));
            double by = padTop + plotH * (1.0 - (snap[i] - min) / range);
            double x = cameraX(bx);
            double y = cameraY(by);
            if (i == 0) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();

        // метка последней цены
        double last = snap[n - 1];
        double byLast = padTop + plotH * (1.0 - (last - min) / range);
        double yLast = cameraY(byLast);
        g.setStroke(Color.web("#22c55e", 0.5));
        g.setLineWidth(1.0);
        g.strokeLine(padLeft, yLast, padLeft + plotW, yLast);
        g.setFill(Color.web("#22c55e"));
        g.fillText(String.format(java.util.Locale.US, "%.4f", last), padLeft + plotW + 4, yLast + 4);

        // подписи
        String sym = currentSymbol;
        g.setFill(Color.web("#ffffff", 0.55));
        g.fillText((sym == null ? "" : sym) + "  " + n + " ticks"
                        + (live ? "" : "  [zoom]  (dbl-click = reset)"),
                padLeft + 2, padTop);
    }

    private double cameraX(double baseX) { return offsetX + baseX * scaleX; }
    private double cameraY(double baseY) { return offsetY + baseY * scaleY; }

    @Override
    public void reset() {
        synchronized (bufLock) { size = 0; head = 0; }
        dirty.set(true);
    }
}