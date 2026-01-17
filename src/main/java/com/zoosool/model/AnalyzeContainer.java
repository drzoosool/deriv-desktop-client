package com.zoosool.model;

public class AnalyzeContainer {

    private static final long ADL_SCALE = 1_000L;

    private final com.zoosool.utils.LongRingBuffer levels;
    private final com.zoosool.utils.LongRingBuffer xmaShort;
    private final com.zoosool.utils.LongRingBuffer adlShortScaled;

    // Inferred tick step (abs delta between neighboring levels), once stable.
    private volatile Long tickStep;

    public AnalyzeContainer(int capacity) {
        this.levels = new com.zoosool.utils.LongRingBuffer(capacity);
        this.xmaShort = new com.zoosool.utils.LongRingBuffer(capacity);
        this.adlShortScaled = new com.zoosool.utils.LongRingBuffer(capacity);
    }

    public void onLevel(long level) {
        levels.add(level);

        // Try infer step only after buffer is full and step is not set
        if (tickStep == null && levels.isFull()) {
            Long step = inferStepIfStable(levels);
            if (step != null) {
                tickStep = step;
            }
        }
    }

    public void onXmaShort(int xma) {
        xmaShort.add(xma);
    }

    public void onAdlShort(double adl) {
        long scaled = Math.round(adl * ADL_SCALE);
        adlShortScaled.add(scaled);
    }

    /**
     * If all abs deltas between consecutive values are equal and > 0, return that delta.
     * Otherwise return null (means: not stable / corrupted sample).
     */
    private static Long inferStepIfStable(com.zoosool.utils.LongRingBuffer buf) {
        int n = buf.size();
        if (n < 2) return null;

        long expected = -1;

        long prev = buf.get(0);
        for (int i = 1; i < n; i++) {
            long cur = buf.get(i);
            long d = cur - prev;
            if (d < 0) d = -d; // abs

            // 0-delta breaks stable step detection
            if (d == 0) {
                return null;
            }

            if (expected < 0) {
                expected = d;
            } else if (d != expected) {
                return null;
            }

            prev = cur;
        }

        return expected > 0 ? expected : null;
    }

    // getters for later experiments (unused now, but handy)
    com.zoosool.utils.LongRingBuffer levels() { return levels; }
    com.zoosool.utils.LongRingBuffer xmaShort() { return xmaShort; }
    com.zoosool.utils.LongRingBuffer adlShortScaled() { return adlShortScaled; }
    Long tickStep() { return tickStep; }
}