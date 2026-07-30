package com.zoosool.analyze;

import com.zoosool.enums.TickDecision;
import com.zoosool.enums.TickStatsState;
import com.zoosool.model.MaPoint;
import com.zoosool.model.TickEvent;
import com.zoosool.model.TickSample;
import com.zoosool.model.TickStatsSnapshot;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultTickStatsCalculator implements TickStatsCalculator {

    public static final int LONG_WINDOW = 120;
    public static final int SHORT_WINDOW = 30;
    public static final int MA_WINDOW = 16;

    public static final int ZERO_DELTA_BAN_THRESHOLD = 2;

    private static final int RESEARCH_WINDOW_SECONDS = 120;

    // периоды MA для сбора значений/стороны/пересечений (на текущем тике)
    private static final int[] MA_PERIODS = {16, 20, 50};

    private final String symbol;
    private final TickStatsSink sink;

    private final double[] quotes = new double[LONG_WINDOW];
    private int size = 0;
    private int head = 0;

    private final Deque<TickSample> researchTicks = new ArrayDeque<>();
    private Instant researchStartedAt = null;

    private String lastQuoteText = null;

    // память знака (price - ma) по каждому периоду — для пересечений на текущем тике
    private final Map<Integer, Integer> prevMaSign = new java.util.HashMap<>();

    public DefaultTickStatsCalculator(String symbol, TickStatsSink sink) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void onEvent(TickEvent event) {
        Objects.requireNonNull(event, "event");

        switch (event.action()) {
            case RESET -> {
                reset();
                sink.onSnapshot(warmupSnapshot(event.receivedAt()));
                return;
            }
            case STOP -> {
                return;
            }
            case TICK -> {
                // fallthrough ниже
            }
            default -> {
                return;
            }
        }

        String qText = event.quote();
        if (qText == null || qText.isBlank()) {
            return;
        }

        double q;
        try {
            q = Double.parseDouble(qText.trim());
        } catch (NumberFormatException ex) {
            return;
        }
        if (Double.isNaN(q) || Double.isInfinite(q)) {
            return;
        }

        lastQuoteText = qText;
        appendQuote(q);
        appendResearchTick(event.receivedAt(), q);

        sink.onSnapshot(buildSnapshot(event.receivedAt()));
    }

    private void reset() {
        Arrays.fill(quotes, 0.0);
        size = 0;
        head = 0;
        lastQuoteText = null;
        prevMaSign.clear();
        clearResearchTicks();

        if (sink instanceof Resetable) {
            ((Resetable) sink).reset();
        }
    }

    private void appendQuote(double quote) {
        quotes[head] = quote;
        head = (head + 1) % LONG_WINDOW;
        if (size < LONG_WINDOW) {
            size++;
        }
    }

    private synchronized void appendResearchTick(Instant at, double quote) {
        if (researchStartedAt == null) {
            researchStartedAt = at;
        }

        researchTicks.addLast(new TickSample(at, quote));

        Instant cutoff = at.minusSeconds(RESEARCH_WINDOW_SECONDS);
        while (!researchTicks.isEmpty() && researchTicks.peekFirst().at().isBefore(cutoff)) {
            researchTicks.removeFirst();
        }
    }

    private synchronized void clearResearchTicks() {
        researchTicks.clear();
        researchStartedAt = null;
    }

    public synchronized List<TickSample> snapshotResearchTicks() {
        if (researchStartedAt == null || researchTicks.isEmpty()) {
            return List.of();
        }

        Instant lastAt = researchTicks.peekLast().at();

        if (lastAt.isBefore(researchStartedAt.plusSeconds(RESEARCH_WINDOW_SECONDS))) {
            return List.of();
        }

        return List.copyOf(researchTicks);
    }

    private TickStatsSnapshot warmupSnapshot(Instant at) {
        return new TickStatsSnapshot(
                symbol,
                TickStatsState.WARMUP_S,
                TickDecision.NA,
                LONG_WINDOW, SHORT_WINDOW, MA_WINDOW,
                0, 0,
                null, null,
                null, null,
                null, null,
                0,
                "RESET",
                at,
                Map.of()                 // movingAverages пусто на warmup
        );
    }

    private TickStatsSnapshot buildSnapshot(Instant at) {
        int bufLong = size;
        int bufShort = Math.min(size, SHORT_WINDOW);

        boolean hasMA = size >= MA_WINDOW;
        boolean hasShort = size >= SHORT_WINDOW;
        boolean hasLong = size >= LONG_WINDOW;

        Double adlShort = hasShort ? computeAdl(bufShort) : null;
        Double adlLong = hasLong ? computeAdl(bufLong) : null;

        Integer xmaShort = (hasMA && hasShort) ? computeMaCrossings(bufShort) : null;
        Integer xmaLong = (hasMA && hasLong) ? computeMaCrossings(bufLong) : null;

        int zeroShort = hasShort ? computeZeroDeltas(bufShort) : 0;

        boolean banned = hasShort && zeroShort >= ZERO_DELTA_BAN_THRESHOLD;

        TickStatsState state = banned
                ? TickStatsState.BANNED
                : (hasLong ? TickStatsState.OK : (hasShort ? TickStatsState.WARMUP_L : TickStatsState.WARMUP_S));

        TickDecision decision = TickDecision.NA;

        String reason = banned ? "ZERO_DELTA>=" + ZERO_DELTA_BAN_THRESHOLD : null;

        Double lastQuote = (size > 0) ? lastQuote() : null;
        String lastQuoteString = (size > 0) ? lastQuoteText : null;

        Map<Integer, MaPoint> movingAverages = computeMovingAverages(lastQuote);

        return new TickStatsSnapshot(
                symbol,
                state,
                decision,
                LONG_WINDOW,
                SHORT_WINDOW,
                MA_WINDOW,
                bufLong,
                bufShort,
                adlLong,
                adlShort,
                xmaLong,
                xmaShort,
                lastQuote,
                lastQuoteString,
                zeroShort,
                reason,
                at,
                movingAverages
        );
    }

    // ── MA value + side + crossing on current tick ───────────────────────
    // На ТЕКУЩЕЙ точке: значение MA, сторона (знак price-ma) и факт пересечения
    // (смена знака относительно прошлого тика). Без прохода по окну.
    private Map<Integer, MaPoint> computeMovingAverages(Double lastQuoteVal) {
        if (lastQuoteVal == null) {
            return Map.of();
        }

        Map<Integer, MaPoint> mas = new LinkedHashMap<>();

        for (int period : MA_PERIODS) {
            Double ma = computeMaLast(period);
            if (ma == null) {
                // не прогрелась — не кладём ключ и знак не обновляем
                continue;
            }

            int sign = Double.compare(lastQuoteVal, ma); // +1 выше, -1 ниже, 0 равно

            Integer prev = prevMaSign.get(period);
            int cross = 0;
            if (prev != null && prev != 0 && sign != 0 && sign != prev) {
                cross = sign; // +1 пересёк снизу вверх, -1 сверху вниз
            }
            prevMaSign.put(period, sign);

            mas.put(period, new MaPoint(period, ma, sign, cross));
        }

        return Map.copyOf(mas);
    }

    /** MA по period точкам от последней записанной точки. Не трогает computeMaAt. */
    private Double computeMaLast(int period) {
        if (size < period) return null;

        int last = head - 1;
        if (last < 0) last += LONG_WINDOW;

        double sum = 0.0;
        for (int k = 0; k < period; k++) {
            int idx = last - k;
            if (idx < 0) idx += LONG_WINDOW;
            sum += quotes[idx];
        }
        return sum / period;
    }

    private double lastQuote() {
        int idx = head - 1;
        if (idx < 0) idx += LONG_WINDOW;
        return quotes[idx];
    }

    private double computeAdl(int windowSize) {
        if (windowSize < 2) return Double.NaN;

        int startIndex = indexOfOldest(windowSize);

        int runsCount = 0;
        int sumRunLengths = 0;

        int currentSign = 0;
        int currentLen = 0;

        double prev = quotes[startIndex];
        for (int i = 1; i < windowSize; i++) {
            int idx = (startIndex + i) % LONG_WINDOW;
            double cur = quotes[idx];

            double d = cur - prev;
            prev = cur;

            int sign = Double.compare(d, 0.0);
            if (sign == 0) {
                continue;
            }

            if (currentSign == 0) {
                currentSign = sign;
                currentLen = 1;
                continue;
            }

            if (sign == currentSign) {
                currentLen++;
            } else {
                runsCount++;
                sumRunLengths += currentLen;
                currentSign = sign;
                currentLen = 1;
            }
        }

        if (currentSign != 0) {
            runsCount++;
            sumRunLengths += currentLen;
        }

        return runsCount == 0 ? 0.0 : (double) sumRunLengths / (double) runsCount;
    }

    private int computeMaCrossings(int windowSize) {
        if (windowSize < Math.max(MA_WINDOW, 2)) return 0;

        int startIndex = indexOfOldest(windowSize);

        int crossings = 0;
        Integer prevSide = null;

        for (int i = 0; i < windowSize; i++) {
            int idx = (startIndex + i) % LONG_WINDOW;

            double ma = computeMaAt(idx);
            double price = quotes[idx];

            int side = Double.compare(price - ma, 0.0);
            if (side == 0) {
                continue;
            }

            if (prevSide != null && side != prevSide) {
                crossings++;
            }
            prevSide = side;
        }
        return crossings;
    }

    private int computeZeroDeltas(int windowSize) {
        if (windowSize < 2) return 0;

        int startIndex = indexOfOldest(windowSize);

        int zeros = 0;
        double prev = quotes[startIndex];
        for (int i = 1; i < windowSize; i++) {
            int idx = (startIndex + i) % LONG_WINDOW;
            double cur = quotes[idx];
            if (Double.compare(cur, prev) == 0) {
                zeros++;
            }
            prev = cur;
        }
        return zeros;
    }

    private double computeMaAt(int idxInclusive) {
        double sum = 0.0;
        for (int k = 0; k < MA_WINDOW; k++) {
            int idx = idxInclusive - k;
            if (idx < 0) idx += LONG_WINDOW;
            sum += quotes[idx];
        }
        return sum / (double) MA_WINDOW;
    }

    private int indexOfOldest(int windowSize) {
        int oldestAll = head - size;
        if (oldestAll < 0) oldestAll += LONG_WINDOW;

        int skip = size - windowSize;
        int idx = oldestAll + skip;
        idx %= LONG_WINDOW;
        return idx;
    }
}
