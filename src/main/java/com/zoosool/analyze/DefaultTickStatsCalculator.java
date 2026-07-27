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
 * - MA side aggregate for L and S windows (sum of sign(price - ma) over window):
 *     > 0  => price mostly ABOVE ma
 *     < 0  => price mostly BELOW ma
 *     == 0 => balanced
 * - zero-delta anomalies (ban if >= ZERO_DELTA_BAN_THRESHOLD in S window)
 *
 * Emission:
 * - pushes TickStatsSnapshot to TickStatsSink every tick (1 tick = 1 sec)
 *
 * NOTE:
 * Decision is intentionally NOT computed here.
 */
public final class DefaultTickStatsCalculator implements TickStatsCalculator {

    // ====== Tuning knobs (static for now) ======
    public static final int LONG_WINDOW = 120;
    public static final int SHORT_WINDOW = 30;
    public static final int MA_WINDOW = 16;

    public static final int ZERO_DELTA_BAN_THRESHOLD = 2;

    // ====== State ======
    private final String symbol;
    private final TickStatsSink sink;

    private final double[] quotes = new double[LONG_WINDOW];
    private int size = 0;
    private int head = 0;

    private String lastQuoteText = null;

    public DefaultTickStatsCalculator(String symbol, TickStatsSink sink) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void onEvent(TickEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.action() == TickAction.RESET) {
            reset();
            // Warmup snapshot immediately after reset — MA side unknown yet => null, null
            sink.onSnapshot(new TickStatsSnapshot(
                    symbol,
                    TickStatsState.WARMUP_S,
                    TickDecision.NA,
                    LONG_WINDOW, SHORT_WINDOW, MA_WINDOW,
                    0, 0,
                    null, null,
                    null, null,
                    null,            // lastQuote (Double)
                    null,            // lastQuoteText (String)
                    0,
                    "RESET",
                    Instant.now(),
                    null
            ));
            return;
        }

        if (event.action() == TickAction.STOP) {
            return;
        }

        if (event.action() != TickAction.TICK) {
            return;
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

        sink.onSnapshot(buildSnapshot());
    }

    private void reset() {
        Arrays.fill(quotes, 0.0);
        size = 0;
        head = 0;
        lastQuoteText = null;

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

        Integer maSide = (size >= MA_WINDOW) ? computeMaSide() : null;

        int zeroShort = hasShort ? computeZeroDeltas(bufShort) : 0;

        boolean banned = hasShort && zeroShort >= ZERO_DELTA_BAN_THRESHOLD;

        TickStatsState state = banned
                ? TickStatsState.BANNED
                : (hasLong ? TickStatsState.OK : (hasShort ? TickStatsState.WARMUP_L : TickStatsState.WARMUP_S));

        TickDecision decision = TickDecision.NA;

        String reason = banned ? "ZERO_DELTA>=" + ZERO_DELTA_BAN_THRESHOLD : null;

        Double lastQuote = (size > 0) ? lastQuote() : null;
        String lastQuoteString = (size > 0) ? lastQuoteText : null;

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
                Instant.now(),
                maSide
        );
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

    private int computeMaSide() {
        if (size < MA_WINDOW) return 0; // MA16 ещё не набралась

        int last = head - 1;
        if (last < 0) last += LONG_WINDOW;

        double ma = computeMaAt(last);   // среднее за последние 16 тиков
        double price = quotes[last];     // цена текущего тика

        return Double.compare(price - ma, 0.0); // +1 / 0 / -1
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
