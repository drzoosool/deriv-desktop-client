package com.zoosool.analyze;

import com.zoosool.enums.TickAction;
import com.zoosool.enums.TickDecision;
import com.zoosool.enums.TickStatsState;
import com.zoosool.model.TickEvent;
import com.zoosool.model.TickStatsSnapshot;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Calculates:
 * - ADL (Average Directional Length) for L and S windows
 * - MA crossings for L and S windows (MA length = MA_WINDOW)
 * - zero-delta anomalies (ban if >= ZERO_DELTA_BAN_THRESHOLD in S window)
 *
 * Emission:
 * - pushes TickStatsSnapshot to TickStatsSink every LOG_EVERY_SECONDS ticks (1 tick = 1 sec)
 */
public final class DefaultTickStatsCalculator implements TickStatsCalculator {

    // ====== Tuning knobs (static for now) ======
    public static final int LONG_WINDOW = 120;
    public static final int SHORT_WINDOW = 30;
    public static final int MA_WINDOW = 16;

    public static final int LOG_EVERY_SECONDS = 2;

    public static final int ZERO_DELTA_BAN_THRESHOLD = 2;

    // Placeholder decision thresholds (tweak later)
    public static final double ADL_OK_LONG = 2.2;
    public static final double ADL_OK_SHORT = 2.2;
    public static final int XMA_TRADE_SHORT_MAX = 4;
    public static final int XMA_CAUTION_SHORT_MAX = 7;
    public static final int XMA_LONG_MAX = 14;

    // ====== State ======
    private final String symbol;
    private final TickStatsSink sink;

    private final double[] quotes = new double[LONG_WINDOW];
    private int size = 0;
    private int head = 0;

    private int secondsSinceLastEmit = 0;

    public DefaultTickStatsCalculator(String symbol, TickStatsSink sink) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void onEvent(TickEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.action() == TickAction.RESET) {
            reset("RESET");
            // optional: emit a warmup snapshot immediately after reset
            sink.onSnapshot(new TickStatsSnapshot(
                    symbol,
                    TickStatsState.WARMUP_S,
                    TickDecision.NA,
                    LONG_WINDOW, SHORT_WINDOW, MA_WINDOW,
                    0, 0,
                    null, null,
                    null, null,
                    0,
                    "RESET",
                    Instant.now()
            ));
            return;
        }

        if (event.action() == TickAction.STOP) {
            // worker will stop after STOP; no periodic emit required here
            return;
        }

        if (event.action() != TickAction.TICK) {
            return;
        }

        Double qObj = event.quote();
        if (qObj == null || qObj.isNaN()) {
            return;
        }

        appendQuote(qObj);

        secondsSinceLastEmit++;
        if (secondsSinceLastEmit >= LOG_EVERY_SECONDS) {
            secondsSinceLastEmit = 0;
            sink.onSnapshot(buildSnapshot());
        }
    }

    private void reset(String reason) {
        Arrays.fill(quotes, 0.0);
        size = 0;
        head = 0;
        secondsSinceLastEmit = 0;
    }

    private void appendQuote(double quote) {
        quotes[head] = quote;
        head = (head + 1) % LONG_WINDOW;
        if (size < LONG_WINDOW) {
            size++;
        }
    }

    private TickStatsSnapshot buildSnapshot() {
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

        TickDecision decision = (state == TickStatsState.OK)
                ? classify(adlLong, xmaLong, adlShort, xmaShort)
                : TickDecision.NA;

        String reason = banned ? "ZERO_DELTA>=" + ZERO_DELTA_BAN_THRESHOLD : null;

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
                zeroShort,
                reason,
                Instant.now()
        );
    }

    /**
     * ADL: average length of consecutive same-sign deltas within last windowSize quotes.
     * - ignores delta==0 for run segmentation
     */
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

    /**
     * Crossings count between price and MA over last windowSize quotes.
     * "Touch" (price==MA) does not flip side and does not count as crossing.
     */
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

    private static TickDecision classify(Double adlLong, Integer xmaLong, Double adlShort, Integer xmaShort) {
        // Defensive: if something is missing, avoid TRADE
        if (adlLong == null || adlShort == null || xmaLong == null || xmaShort == null) {
            return TickDecision.NO_TRADE;
        }

        boolean longOk = adlLong >= ADL_OK_LONG && xmaLong <= XMA_LONG_MAX;

        // Short window is the "now" signal for 6s trades
        if (adlShort >= ADL_OK_SHORT && xmaShort <= XMA_TRADE_SHORT_MAX && longOk) {
            return TickDecision.TRADE;
        }

        if (longOk && (adlShort >= 2.0 && xmaShort <= XMA_CAUTION_SHORT_MAX)) {
            return TickDecision.CAUTION;
        }

        return TickDecision.NO_TRADE;
    }
}
