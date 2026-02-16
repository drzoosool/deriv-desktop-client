// GlobalLevelTape.java
package com.zoosool.analyze;

/**
 * Fixed-size 1 Hz tape (ring buffer): stores last N seconds of (epochSecond -> level).
 *
 * Not thread-safe by itself. Use from synchronized context.
 */
public final class GlobalLevelTape {

    private final int keepSeconds;
    private final long[] epochAt;
    private final long[] levelAt;
    private final boolean[] present;

    public GlobalLevelTape(int keepSeconds) {
        if (keepSeconds <= 0) throw new IllegalArgumentException("keepSeconds must be > 0");
        this.keepSeconds = keepSeconds;
        this.epochAt = new long[keepSeconds];
        this.levelAt = new long[keepSeconds];
        this.present = new boolean[keepSeconds];
    }

    public int keepSeconds() {
        return keepSeconds;
    }

    public void put(long epochSecond, long level) {
        int idx = index(epochSecond);
        epochAt[idx] = epochSecond;
        levelAt[idx] = level;
        present[idx] = true;
    }

    public void clear() {
        for (int i = 0; i < keepSeconds; i++) {
            present[i] = false;
        }
    }

    public Long getExact(long epochSecond) {
        int idx = index(epochSecond);
        if (!present[idx]) return null;
        if (epochAt[idx] != epochSecond) return null;
        return levelAt[idx];
    }

    /**
     * Snapshot last keepSeconds ending at toEpochInclusive as RLE with timestamps:
     * [{"t":<epoch>,"l":<level>,"n":<len>}, {"t":<epoch>,"gap":<len>}, ...]
     */
    public String snapshotLastRleJsonWithTimestamps(long toEpochInclusive) {
        long fromEpoch = Math.max(0, toEpochInclusive - (keepSeconds - 1L));
        return snapshotRleJsonWithTimestamps(fromEpoch, toEpochInclusive);
    }

    private String snapshotRleJsonWithTimestamps(long fromEpoch, long toEpoch) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append('[');

        boolean first = true;

        Long runLevel = null;
        long runStart = -1;
        int runLen = 0;

        long gapStart = -1;
        int gapLen = 0;

        for (long e = fromEpoch; e <= toEpoch; e++) {
            Long v = getExact(e);

            if (v == null) {
                if (runLen > 0) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"t\":").append(runStart)
                            .append(",\"l\":").append(runLevel)
                            .append(",\"n\":").append(runLen).append('}');
                    runLevel = null;
                    runStart = -1;
                    runLen = 0;
                }
                if (gapLen == 0) gapStart = e;
                gapLen++;
                continue;
            }

            if (gapLen > 0) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"t\":").append(gapStart)
                        .append(",\"gap\":").append(gapLen).append('}');
                gapLen = 0;
                gapStart = -1;
            }

            if (runLen == 0) {
                runLevel = v;
                runStart = e;
                runLen = 1;
            } else if (v.longValue() == runLevel.longValue()) {
                runLen++;
            } else {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"t\":").append(runStart)
                        .append(",\"l\":").append(runLevel)
                        .append(",\"n\":").append(runLen).append('}');
                runLevel = v;
                runStart = e;
                runLen = 1;
            }
        }

        if (runLen > 0) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"t\":").append(runStart)
                    .append(",\"l\":").append(runLevel)
                    .append(",\"n\":").append(runLen).append('}');
        }
        if (gapLen > 0) {
            if (!first) sb.append(',');
            sb.append("{\"t\":").append(gapStart)
                    .append(",\"gap\":").append(gapLen).append('}');
        }

        sb.append(']');
        return sb.toString();
    }

    private int index(long epochSecond) {
        long m = epochSecond % keepSeconds;
        if (m < 0) m += keepSeconds;
        return (int) m;
    }
}
