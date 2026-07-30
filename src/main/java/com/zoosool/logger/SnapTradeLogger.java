// SnapTradeLogger.java
package com.zoosool.logger;

import com.zoosool.model.MaPoint;
import com.zoosool.model.TickSample;
import com.zoosool.model.TickStatsSnapshot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Асинхронный логгер SNAP-ставок.
 * start() при старте SNAP-торгов создаёт файл, log() пишет JSON-строку на каждый результат.
 * Вся работа с файлом — на одном демон-потоке, торговый поток не блокируется.
 */
public final class SnapTradeLogger {

    public record Entry(
            long tradeSeq,
            String at,                  // время результата (ISO)
            String signalAt,            // время тика, на котором появился сигнал
            String sentAt,              // время отправки сделки
            String symbol,
            String direction,           // исходное направление сигнала UP / DOWN
            String tradeDirection,      // реально отправленное направление UP / DOWN
            BigDecimal stake,           // размер ставки
            String result,              // SUCCESS / FAIL
            int ladderStep,             // номер серии лестницы
            String error,
            TickStatsSnapshot snapshot, // состояние статистики на момент сигнала
            List<TickSample> ticks       // последние 120 секунд до сигнала
    ) {
        public Entry {
            ticks = ticks == null ? List.of() : List.copyOf(ticks);
        }
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path baseDir;
    private final Consumer<String> errLog;
    private final ZoneId zone = ZoneId.systemDefault();
    private final ExecutorService io;

    // трогается только потоком io
    private BufferedWriter writer = null;

    public SnapTradeLogger(Path baseDir, Consumer<String> errLog) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.errLog = (errLog == null) ? s -> {} : errLog;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "snap-trade-logger");
            t.setDaemon(true);
            return t;
        };
        this.io = Executors.newSingleThreadExecutor(tf);
    }

    /** Старт SNAP-торгов: создаёт новый файл. */
    public void start(String symbol) {
        final String sym = (symbol == null || symbol.isBlank()) ? "unknown" : symbol.trim();
        submit(() -> openNew(sym));
    }

    /** Запись результата сделки. Если файл не открыт — игнор. */
    public void log(Entry e) {
        if (e == null) return;
        submit(() -> {
            if (writer != null) writeLine(toJson(e));
        });
    }

    /** Стоп стратегии: закрыть текущий файл. Поток остаётся жив для нового старта. */
    public void stop() {
        submit(this::closeWriter);
    }

    /** Завершение приложения: закрыть файл и остановить поток. */
    public void close() {
        submit(this::closeWriter);
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            io.shutdownNow();
        }
    }

    // ── только поток io ──────────────────────────────────────────────

    private void openNew(String symbol) {
        closeWriter();
        try {
            Files.createDirectories(baseDir);
            String name = "snap-" + symbol + "-" + LocalDateTime.now(zone).format(TS) + ".jsonl";
            Path file = baseDir.resolve(name);
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            String msg = "SnapTradeLogger: файл создан " + file.toAbsolutePath();
            errLog.accept(msg);
            System.out.println(msg); // подстраховка на случай пустого errLog
        } catch (Exception ex) {
            String msg = "SnapTradeLogger.open FAILED: " + ex;
            errLog.accept(msg);
            System.out.println(msg);
            ex.printStackTrace();
            writer = null;
        }
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ex) {
            errLog.accept("SnapTradeLogger.close failed: " + ex);
        }
        writer = null;
    }

    private void writeLine(String json) {
        try {
            writer.write(json);
            writer.write('\n');
            writer.flush();
        } catch (IOException ex) {
            errLog.accept("SnapTradeLogger.write failed: " + ex);
        }
    }

    private void submit(Runnable r) {
        try {
            io.execute(r);
        } catch (RuntimeException ex) {
            errLog.accept("SnapTradeLogger submit rejected: " + ex);
        }
    }

    private static String toJson(Entry e) {
        StringBuilder b = new StringBuilder(8192);

        b.append("{")
                .append("\"tradeSeq\":").append(e.tradeSeq())
                .append(",\"at\":\"").append(esc(e.at())).append("\"")
                .append(",\"signalAt\":\"").append(esc(e.signalAt())).append("\"")
                .append(",\"sentAt\":\"").append(esc(e.sentAt())).append("\"")
                .append(",\"symbol\":\"").append(esc(e.symbol())).append("\"")
                .append(",\"direction\":\"").append(esc(e.direction())).append("\"")
                .append(",\"tradeDirection\":\"").append(esc(e.tradeDirection())).append("\"")
                .append(",\"stake\":").append(e.stake() == null ? "null" : e.stake().toPlainString())
                .append(",\"result\":\"").append(esc(e.result())).append("\"")
                .append(",\"ladderStep\":").append(e.ladderStep())
                .append(",\"error\":").append(nullableString(e.error()))
                .append(",\"snapshot\":");

        appendSnapshot(b, e.snapshot());

        b.append(",\"ticks\":[");

        List<TickSample> ticks = e.ticks();
        for (int i = 0; i < ticks.size(); i++) {
            if (i > 0) b.append(",");

            TickSample tick = ticks.get(i);

            b.append("{")
                    .append("\"at\":\"").append(esc(tick.at().toString())).append("\"")
                    .append(",\"quote\":").append(tick.quote())
                    .append("}");
        }

        b.append("]")
                .append("}");

        return b.toString();
    }

    private static void appendSnapshot(StringBuilder b, TickStatsSnapshot snapshot) {
        if (snapshot == null) {
            b.append("null");
            return;
        }

        b.append("{")
                .append("\"symbol\":\"").append(esc(snapshot.symbol())).append("\"")
                .append(",\"state\":\"").append(snapshot.state().name()).append("\"")
                .append(",\"decision\":\"").append(snapshot.decision().name()).append("\"")
                .append(",\"longWindow\":").append(snapshot.longWindow())
                .append(",\"shortWindow\":").append(snapshot.shortWindow())
                .append(",\"maWindow\":").append(snapshot.maWindow())
                .append(",\"bufLong\":").append(snapshot.bufLong())
                .append(",\"bufShort\":").append(snapshot.bufShort())
                .append(",\"adlLong\":").append(nullableDouble(snapshot.adlLong()))
                .append(",\"adlShort\":").append(nullableDouble(snapshot.adlShort()))
                .append(",\"xmaLong\":").append(nullableInteger(snapshot.xmaLong()))
                .append(",\"xmaShort\":").append(nullableInteger(snapshot.xmaShort()))
                .append(",\"lastQuote\":").append(nullableDouble(snapshot.lastQuote()))
                .append(",\"lastQuoteString\":").append(nullableString(snapshot.lastQuoteString()))
                .append(",\"zeroShort\":").append(snapshot.zeroShort())
                .append(",\"reason\":").append(nullableString(snapshot.reason()))
                .append(",\"at\":\"").append(esc(snapshot.at().toString())).append("\"")
                .append(",\"movingAverages\":");

        appendMovingAverages(b, snapshot.movingAverages());

        b.append("}");
    }

    private static void appendMovingAverages(StringBuilder b, Map<Integer, MaPoint> movingAverages) {
        b.append("{");

        if (movingAverages != null && !movingAverages.isEmpty()) {
            boolean first = true;

            for (Map.Entry<Integer, MaPoint> entry : new TreeMap<>(movingAverages).entrySet()) {
                if (!first) b.append(",");
                first = false;

                MaPoint ma = entry.getValue();

                b.append("\"").append(entry.getKey()).append("\":{")
                        .append("\"period\":").append(ma.period())
                        .append(",\"value\":").append(ma.value())
                        .append(",\"side\":").append(ma.side())
                        .append(",\"cross\":").append(ma.cross())
                        .append("}");
            }
        }

        b.append("}");
    }

    private static String nullableString(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
    }

    private static String nullableInteger(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private static String nullableDouble(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) return "null";
        return value.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }
}
