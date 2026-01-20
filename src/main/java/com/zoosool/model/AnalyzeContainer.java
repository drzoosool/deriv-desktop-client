package com.zoosool.model;

import com.zoosool.utils.LongRingBuffer;

import java.time.Instant;

/**
 * Per-symbol rolling analysis state (last N samples).
 *
 * Buffers:
 * - levels: price levels (long) derived from original quote string
 * - xmaShort / xmaLong: MA-crossings counts
 * - adlShortScaled / adlLongScaled: ADL scaled to long (x1000)
 * - zeroShort: zero-delta count in short window
 *
 * Threading:
 * - Snapshot methods are safe enough for concurrent reads.
 * - Writes are synchronized per-buffer.
 */
public final class AnalyzeContainer {

    private static final long ADL_SCALE = 1_000L;

    private final LongRingBuffer levels;

    private final LongRingBuffer xmaShort;
    private final LongRingBuffer xmaLong;

    private final LongRingBuffer adlShortScaled;
    private final LongRingBuffer adlLongScaled;

    private final LongRingBuffer zeroShort;

    // Inferred tick step (abs delta between neighboring levels), once stable.
    private volatile Long tickStep;

    // For debugging/freshness
    private volatile long lastSnapshotAtEpochMs = 0;

    public AnalyzeContainer(int capacity) {
        this.levels = new LongRingBuffer(capacity);

        this.xmaShort = new LongRingBuffer(capacity);
        this.xmaLong = new LongRingBuffer(capacity);

        this.adlShortScaled = new LongRingBuffer(capacity);
        this.adlLongScaled = new LongRingBuffer(capacity);

        this.zeroShort = new LongRingBuffer(capacity);
    }

    public int capacity() {
        return levels.capacity();
    }

    /** Stores the snapshot time (debug / freshness). */
    public void onSnapshotAt(Instant at) {
        if (at != null) {
            lastSnapshotAtEpochMs = at.toEpochMilli();
        }
    }

    public void onLevel(long level) {
        synchronized (levels) {
            levels.add(level);

            if (tickStep == null && levels.isFull()) {
                Long step = inferStepIfStable(levels);
                if (step != null) {
                    tickStep = step;
                }
            }
        }
    }

    public void onXmaShort(int xma) {
        synchronized (xmaShort) {
            xmaShort.add(xma);
        }
    }

    public void onXmaLong(int xma) {
        synchronized (xmaLong) {
            xmaLong.add(xma);
        }
    }

    public void onAdlShort(double adl) {
        long scaled = Math.round(adl * ADL_SCALE);
        synchronized (adlShortScaled) {
            adlShortScaled.add(scaled);
        }
    }

    public void onAdlLong(double adl) {
        long scaled = Math.round(adl * ADL_SCALE);
        synchronized (adlLongScaled) {
            adlLongScaled.add(scaled);
        }
    }

    public void onZeroShort(int z) {
        synchronized (zeroShort) {
            zeroShort.add(z);
        }
    }

    // ===== trading precondition =====

    /**
     * Your requested precondition: do NOT trade until all required metrics exist.
     *
     * Here "required" means:
     * - levels are full (we have N last levels + can do edge logic)
     * - last xmaShort, xmaLong exist
     * - last adlShort, adlLong exist
     * - last zeroShort exists
     *
     * If you later decide tickStep must also be inferred, add: && tickStep != null
     */
    public boolean hasAllRequired() {
        LevelsEdgesSnapshot edges = snapshotLevelsEdges();
        if (!edges.full()) return false;

        MetricsSnapshot m = snapshotMetrics();
        return m.hasAllRequired();
    }

    // ===== snapshots =====

    /** First/last for cooldown logic. */
    public LevelsEdgesSnapshot snapshotLevelsEdges() {
        synchronized (levels) {
            int n = levels.size();
            boolean full = levels.isFull();
            if (n == 0) {
                return new LevelsEdgesSnapshot(false, 0, 0, 0);
            }
            long first = levels.get(0);
            long last = levels.get(n - 1);
            return new LevelsEdgesSnapshot(full, n, first, last);
        }
    }

    /** Debug-friendly: edges + uniqueCount + range + tickStep. */
    public LevelsStatsSnapshot snapshotLevelsStats() {
        synchronized (levels) {
            int n = levels.size();
            boolean full = levels.isFull();
            if (n == 0) {
                return new LevelsStatsSnapshot(false, 0, 0, 0, 0, 0, tickStep);
            }
            long first = levels.get(0);
            long last = levels.get(n - 1);
            int uniq = levels.uniqueCount();
            long range = levels.range();
            return new LevelsStatsSnapshot(full, n, first, last, uniq, range, tickStep);
        }
    }

    /** Latest values of all metrics (nullable if buffer is empty). */
    public MetricsSnapshot snapshotMetrics() {
        Integer xS = lastInt(xmaShort);
        Integer xL = lastInt(xmaLong);

        Double aS = lastAdl(adlShortScaled);
        Double aL = lastAdl(adlLongScaled);

        Integer zS = lastInt(zeroShort);

        return new MetricsSnapshot(
                xS, xL,
                aS, aL,
                zS,
                lastSnapshotAtEpochMs
        );
    }

    private static Integer lastInt(LongRingBuffer buf) {
        synchronized (buf) {
            int n = buf.size();
            if (n == 0) return null;
            return (int) buf.get(n - 1);
        }
    }

    private static Double lastAdl(LongRingBuffer scaledBuf) {
        synchronized (scaledBuf) {
            int n = scaledBuf.size();
            if (n == 0) return null;
            long scaled = scaledBuf.get(n - 1);
            return scaled / (double) ADL_SCALE;
        }
    }

    /**
     * If all abs deltas between consecutive values are equal and > 0, return that delta.
     * Otherwise return null.
     */
    private static Long inferStepIfStable(LongRingBuffer buf) {
        int n = buf.size();
        if (n < 2) return null;

        long expected = -1;

        long prev = buf.get(0);
        for (int i = 1; i < n; i++) {
            long cur = buf.get(i);
            long d = cur - prev;
            if (d < 0) d = -d;

            if (d == 0) return null;

            if (expected < 0) expected = d;
            else if (d != expected) return null;

            prev = cur;
        }
        return expected > 0 ? expected : null;
    }

    // ===== record snapshots =====

    public record LevelsEdgesSnapshot(boolean full, int size, long first, long last) {}

    public record LevelsStatsSnapshot(
            boolean full,
            int size,
            long first,
            long last,
            int uniqueCount,
            long range,
            Long tickStep
    ) {}

    public record MetricsSnapshot(
            Integer xmaShort,
            Integer xmaLong,
            Double adlShort,
            Double adlLong,
            Integer zeroShort,
            long lastAtEpochMs
    ) {
        public boolean hasAllRequired() {
            return xmaShort != null
                    && xmaLong != null
                    && adlShort != null
                    && adlLong != null
                    && zeroShort != null
                    && lastAtEpochMs > 0;
        }
    }
}
