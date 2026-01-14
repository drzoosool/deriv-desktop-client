package com.zoosool.analyze;

import com.zoosool.model.TickEvent;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Calculates simple market "quality" stats for turbo options:
 * - ADL (Average Directional Length) for 120s and 30s windows
 * - Crossings count for MA(16) for 120s and 30s windows
 * - Zero-delta anomaly detection (ban when >= 2 within short window)
 * <p>
 * Notes:
 * - 1 tick == 1 second (as per your domain rule).
 * - For simplicity and correctness, metrics are recomputed from ring buffers periodically (each tick).
 * With window sizes 120/30, it's cheap and easier to debug.
 * - RESET clears all state. STOP just logs and returns.
 */
public final class DefaultTickStatsCalculator implements TickStatsCalculator {

    // ====== Tunable parameters (static for now, easy to tweak) ======

    /**
     * Long window length in ticks (seconds).
     */
    public static final int LONG_WINDOW = 120;

    /**
     * Short window length in ticks (seconds).
     */
    public static final int SHORT_WINDOW = 30;

    /**
     * Moving average window length in ticks (seconds).
     */
    public static final int MA_WINDOW = 16;

    /**
     * Recompute & log cadence (seconds).
     */
    public static final int LOG_EVERY_SECONDS = 2;

    /**
     * If >= this many zero deltas occur in the short window -> ban (abnormal mode).
     */
    public static final int ZERO_DELTA_BAN_THRESHOLD = 2;

    /**
     * Thresholds for decision classification (initial placeholders, tweak later).
     */
    public static final double ADL_OK_LONG = 2.0;
    public static final double ADL_OK_SHORT = 2.0;
    public static final int XMA_BAD_SHORT = 8;  // e.g. too many MA crossings in 30s
    public static final int XMA_BAD_LONG = 20;  // e.g. too many MA crossings in 120s

    // ====== State ======

    private final String symbol;
    private final Consumer<String> log;

    // ring buffer of quotes for the long window (also used as base for short window)
    private final double[] quotes = new double[LONG_WINDOW];
    private int size = 0;      // filled samples, <= LONG_WINDOW
    private int head = 0;      // next write position (0..LONG_WINDOW-1)

    private Double lastQuote = null;

    private int secondsSinceLastLog = 0;

    public DefaultTickStatsCalculator(String symbol, Consumer<String> log) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public void onStart() {
        log.accept("[STATS] symbol=" + symbol + " state=CALC_START");
    }

    @Override
    public void onStop() {
        log.accept("[STATS] symbol=" + symbol + " state=CALC_STOP");
    }

    @Override
    public void onEvent(TickEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.action() == TickAction.RESET) {
            reset("RESET");
            return;
        }
        if (event.action() == TickAction.STOP) {
            // Nothing special: worker will stop after STOP, but we can log once.
            log.accept("[STATS] symbol=" + symbol + " state=STOP_EVENT");
            return;
        }
        if (event.action() != TickAction.TICK) {
            log.accept("[STATS] symbol=" + symbol + " state=IGNORED action=" + event.action());
            return;
        }

        Double qObj = event.quote();
        if (qObj == null || qObj.isNaN()) {
            // Skip malformed tick
            return;
        }

        double quote = qObj;

        appendQuote(quote);

        secondsSinceLastLog++;
        if (secondsSinceLastLog >= LOG_EVERY_SECONDS) {
            secondsSinceLastLog = 0;
            logSnapshot();
        }
    }

    private void reset(String reason) {
        Arrays.fill(quotes, 0.0);
        size = 0;
        head = 0;
        lastQuote = null;
        secondsSinceLastLog = 0;

        log.accept("[STATS] symbol=" + symbol + " state=RESET reason=" + reason + " at=" + Instant.now());
    }

    private void appendQuote(double quote) {
        // write
        quotes[head] = quote;
        head = (head + 1) % LONG_WINDOW;
        if (size < LONG_WINDOW) {
            size++;
        }
        lastQuote = quote;
    }

    private void logSnapshot() {
        int bufLong = size;
        int bufShort = Math.min(size, SHORT_WINDOW);

        // Warming phases
        boolean hasMA = size >= MA_WINDOW;
        boolean hasShort = size >= SHORT_WINDOW;
        boolean hasLong = size >= LONG_WINDOW;

        // Compute metrics (only when enough data; else NA)
        Double adlShort = hasShort ? computeAdl(bufShort) : null;
        Double adlLong = hasLong ? computeAdl(bufLong) : null;

        Integer xmaShort = (hasMA && hasShort) ? computeMaCrossings(bufShort) : null;
        Integer xmaLong = (hasMA && hasLong) ? computeMaCrossings(bufLong) : null;

        int zeroShort = hasShort ? computeZeroDeltas(bufShort) : 0;

        // Ban rule (abnormal mode)
        boolean banned = hasShort && zeroShort >= ZERO_DELTA_BAN_THRESHOLD;

        String state = banned
                ? "BANNED"
                : (hasLong ? "OK" : (hasShort ? "WARMUP_L" : "WARMUP_S"));

        String decision = (banned || !hasLong)
                ? "NA"
                : classify(adlLong, xmaLong, adlShort, xmaShort);

        StringBuilder sb = new StringBuilder(256);
        sb.append("[STATS] symbol=").append(symbol)
                .append(" state=").append(state)
                .append(" decision=").append(decision)

                .append(" adlL=").append(adlLong != null ? fmt2(adlLong) : "NA")
                .append(" adlS=").append(adlShort != null ? fmt2(adlShort) : "NA")
                .append(" xmaL=").append(xmaLong != null ? xmaLong : "NA")
                .append(" xmaS=").append(xmaShort != null ? xmaShort : "NA")
                .append(" zS=").append(hasShort ? zeroShort : 0);


        sb.append(" L=").append(LONG_WINDOW)
                .append(" S=").append(SHORT_WINDOW)
                .append(" MA=").append(MA_WINDOW)
                .append(" bufL=").append(bufLong).append("/").append(LONG_WINDOW)
                .append(" bufS=").append(bufShort).append("/").append(SHORT_WINDOW);

        if (banned) {
            sb.append(" reason=ZERO_DELTA>= ").append(ZERO_DELTA_BAN_THRESHOLD);
        }

        log.accept(sb.toString());
    }

    private double computeAdl(int windowSize) {
        // Need at least 2 points
        if (windowSize < 2) return Double.NaN;

        int startIndex = indexOfOldest(windowSize);

        int runsCount = 0;
        int sumRunLengths = 0;

        int currentSign = 0; // -1/+1, 0 means not started
        int currentLen = 0;

        double prev = quotes[startIndex];
        for (int i = 1; i < windowSize; i++) {
            int idx = (startIndex + i) % LONG_WINDOW;
            double cur = quotes[idx];

            double d = cur - prev;
            prev = cur;

            int sign = Double.compare(d, 0.0);
            if (sign == 0) {
                // ignore zero delta for ADL segmentation
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

        if (runsCount == 0) {
            // All deltas were zero (unlikely in your normal mode)
            return 0.0;
        }
        return (double) sumRunLengths / (double) runsCount;
    }

    private int computeMaCrossings(int windowSize) {
        // Need at least MA_WINDOW to compute first MA, and 2 points to detect crossings.
        if (windowSize < Math.max(MA_WINDOW, 2)) return 0;

        int startIndex = indexOfOldest(windowSize);

        int crossings = 0;

        Integer prevSide = null; // sign of (price - ma) at previous tick
        for (int i = 0; i < windowSize; i++) {
            int idx = (startIndex + i) % LONG_WINDOW;

            // MA for this tick uses MA_WINDOW ending at idx inclusive
            double ma = computeMaAt(idx);
            double price = quotes[idx];

            int side = Double.compare(price - ma, 0.0);
            if (side == 0) {
                // Treat "touch" as neutral; keep prevSide unchanged to avoid fake crossings
                continue;
            }

            if (prevSide != null && side != prevSide) {
                crossings++;
            }
            prevSide = side;
        }
        return crossings;
    }

    /**
     * Counts the number of zero deltas (price unchanged) within last N quotes.
     */
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
        // sum last MA_WINDOW quotes ending at idxInclusive
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

    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String classify(Double adlLong, Integer xmaLong, Double adlShort, Integer xmaShort) {
        boolean longOk = adlLong != null && adlLong >= ADL_OK_LONG && xmaLong != null && xmaLong < XMA_BAD_LONG;
        boolean shortOk = adlShort != null && adlShort >= ADL_OK_SHORT && xmaShort != null && xmaShort < XMA_BAD_SHORT;

        if (longOk && shortOk) return "TRADE";
        if (longOk) return "CAUTION";
        return "NO_TRADE";
    }
}
