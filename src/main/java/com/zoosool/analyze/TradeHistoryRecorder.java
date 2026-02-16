// TradeHistoryRecorder.java
package com.zoosool.analyze;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Writes one JSONL record per CLOSED trade (type=TRADE).
 * Flushes every record to reduce data loss on crash.
 */
public final class TradeHistoryRecorder implements AutoCloseable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Consumer<String> log;
    private final Path file;
    private final BufferedWriter out;

    private boolean closed;

    public TradeHistoryRecorder(Path dir, Consumer<String> log) {
        this.log = (log == null) ? s -> {} : log;

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create dir: " + dir, e);
        }

        String pid = safePid();
        String name = "trades-" + LocalDateTime.now().format(TS) + "-pid" + pid + ".jsonl";
        this.file = dir.resolve(name);

        try {
            this.out = Files.newBufferedWriter(
                    file,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open file: " + file, e);
        }

        this.log.accept("📄 trade dataset file: " + file.toAbsolutePath());
    }

    public Path file() {
        return file;
    }

    /**
     * One record on close. timelineRleJson must be a JSON array string (already escaped/valid).
     */
    public synchronized void recordTradeClosed(
            long eventEpochSecond,
            String symbol,
            long tradeSeq,

            long tradeStartEpochSecond,
            int durationSeconds,

            String stakeType,
            String stakePerSide,
            int ladderIdxAtSend,
            int prevLadderIdx,
            int newLadderIdx,
            String nextStakePerSide,

            String result,
            String exception,
            long cooldownUntilEpochSecond,

            long timelineToEpochSecond,
            int timelineSeconds,
            String timelineRleJson
    ) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(stakeType, "stakeType");
        Objects.requireNonNull(stakePerSide, "stakePerSide");
        Objects.requireNonNull(nextStakePerSide, "nextStakePerSide");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(timelineRleJson, "timelineRleJson");

        long expectedExitEpochSecond = tradeStartEpochSecond + durationSeconds;
        long timelineFromEpochSecond = Math.max(0, timelineToEpochSecond - (timelineSeconds - 1L));

        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');

        kv(sb, "type", "TRADE").append(',');
        kv(sb, "eventEpochSecond", eventEpochSecond).append(',');
        kv(sb, "symbol", symbol).append(',');
        kv(sb, "tradeSeq", tradeSeq).append(',');

        sb.append("\"trade\":{");
        kv(sb, "startEpochSecond", tradeStartEpochSecond).append(',');
        kv(sb, "durationSeconds", durationSeconds).append(',');
        kv(sb, "expectedExitEpochSecond", expectedExitEpochSecond).append(',');
        kv(sb, "resultEpochSecond", timelineToEpochSecond).append(',');
        kv(sb, "latencySec", Math.max(0, timelineToEpochSecond - tradeStartEpochSecond));
        sb.append("},");

        sb.append("\"stake\":{");
        kv(sb, "stakeType", stakeType).append(',');
        kv(sb, "stakePerSide", stakePerSide).append(',');
        kv(sb, "ladderIdxAtSend", ladderIdxAtSend).append(',');
        kv(sb, "prevLadderIdx", prevLadderIdx).append(',');
        kv(sb, "newLadderIdx", newLadderIdx).append(',');
        kv(sb, "nextStakePerSide", nextStakePerSide);
        sb.append("},");

        sb.append("\"outcome\":{");
        kv(sb, "result", result).append(',');
        if (exception != null) kv(sb, "exception", exception); else sb.append("\"exception\":null");
        sb.append(',');
        kv(sb, "cooldownUntilEpochSecond", cooldownUntilEpochSecond);
        sb.append("},");

        sb.append("\"timeline\":{");
        kv(sb, "fromEpochSecond", timelineFromEpochSecond).append(',');
        kv(sb, "toEpochSecond", timelineToEpochSecond).append(',');
        kv(sb, "seconds", timelineSeconds).append(',');
        sb.append("\"rle\":").append(timelineRleJson);
        sb.append("}");

        sb.append('}');

        writeLine(sb.toString());
    }

    private void writeLine(String line) {
        if (closed) return;
        try {
            out.write(line);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            log.accept("⚠️ dataset write failed: " + e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { out.flush(); } catch (IOException ignored) {}
        try { out.close(); } catch (IOException ignored) {}
    }

    private static String safePid() {
        try {
            String jvmName = ManagementFactory.getRuntimeMXBean().getName(); // "pid@hostname"
            int idx = jvmName.indexOf('@');
            return (idx > 0) ? jvmName.substring(0, idx) : jvmName;
        } catch (Exception e) {
            return "na";
        }
    }

    // --- JSON helpers (minimal) ---
    private static StringBuilder kv(StringBuilder sb, String key, String val) {
        sb.append('"').append(escape(key)).append("\":\"").append(escape(val)).append('"');
        return sb;
    }

    private static StringBuilder kv(StringBuilder sb, String key, long val) {
        sb.append('"').append(escape(key)).append("\":").append(val);
        return sb;
    }

    private static StringBuilder kv(StringBuilder sb, String key, int val) {
        sb.append('"').append(escape(key)).append("\":").append(val);
        return sb;
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append("\\u").append(String.format("%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
