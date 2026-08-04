package com.zoosool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Выгрузка ТИКОВОЙ истории step-индексов за N дней в отдельные CSV.
 *
 * Цепочка: PAT -> GET /accounts -> demo -> POST /otp -> WS.
 * История берётся через WS ticks_history (НЕ REST), постранично назад по времени:
 *   Deriv отдаёт максимум 5000 тиков за запрос -> листаем end'ом, пока не уйдём за границу.
 *
 * ЗАПУСК: обычный main, JavaFX не нужен.
 * ВНИМАНИЕ: PAT захардкожен только для теста. Не коммитить. Скоуп: read/trade.
 */
public class DerivExportHistory {

    // ── КОНФИГ ────────────────────────────────────────────────────────
    static final String APP_ID    = "33TPtshmMkxSrdZoHQ8mt";
    static final String PAT       = "pat_92adc1c8feb022b1f51b083e0e644c985f6d5dad496beaa4eea9eba81b5d5bb2";
    static final String REST_BASE = "https://api.derivws.com/trading/v1/options";

    static final String[] SYMBOLS = {"stpRNG", "stpRNG2", "stpRNG3", "stpRNG4", "stpRNG5"};

    static final int DAYS = 1;                    // окно назад от момента запуска (сутки)
    static final int PAGE = 5000;                 // потолок ticks_history за запрос
    static final Path OUT_DIR = Path.of("history-export");

    // штамп для имён файлов — в ЛОКАЛЬНОЙ зоне (то, что ты видишь на часах),
    // тогда как окно истории считается в epoch и от зоны не зависит
    static final String RUN_STAMP =
            LocalDateTime.now(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

    // защита от бесконечного листания, если сервер поведёт себя неожиданно
    static final int MAX_PAGES_PER_SYMBOL = 200;

    static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        if (APP_ID.startsWith("REPLACE") || PAT.startsWith("REPLACE")) {
            System.err.println("Впиши APP_ID и PAT в начале файла.");
            return;
        }

        // ── ФАЗА 1: accounts -> demo ─────────────────────────────────
        String accountsRaw = restGet("/accounts");
        JsonNode accounts = M.readTree(accountsRaw).path("data");
        if (!accounts.isArray() || accounts.isEmpty()) {
            System.err.println("data пустой или не массив:\n" + pretty(accountsRaw));
            return;
        }

        JsonNode demo = null;
        for (JsonNode a : accounts) {
            if (a.path("account_type").asText("").equalsIgnoreCase("demo")
                    && a.path("status").asText("").equalsIgnoreCase("active")) {
                demo = a;
                break;
            }
        }
        if (demo == null) {
            System.err.println("Активный demo не найден:\n" + pretty(accountsRaw));
            return;
        }
        String accountId = demo.path("account_id").asText();
        System.out.printf(">>> demo account_id=%s%n", accountId);

        // ── ФАЗА 2: OTP -> WS URL ────────────────────────────────────
        String otpRaw = restPost("/accounts/" + accountId + "/otp", "");
        String wsUrl = M.readTree(otpRaw).path("data").path("url").asText(null);
        if (wsUrl == null || wsUrl.isBlank()) {
            System.err.println("Нет data.url:\n" + pretty(otpRaw));
            return;
        }
        System.out.println(">>> ws url (otp скрыт): " + redact(wsUrl));

        // ── ФАЗА 3: качаем ───────────────────────────────────────────
        Files.createDirectories(OUT_DIR);

        Exporter exporter = new Exporter(wsUrl);
        exporter.connectBlocking();

        long nowSec = Instant.now().getEpochSecond();
        long sinceSec = nowSec - (long) DAYS * 86400L;

        for (String symbol : SYMBOLS) {
            try {
                exporter.dumpSymbol(symbol, nowSec, sinceSec);
            } catch (Exception e) {
                System.err.println("!! " + symbol + " упал: " + e);
                e.printStackTrace();
            }
        }

        exporter.close();
        System.out.println("Готово. Файлы в " + OUT_DIR.toAbsolutePath());
    }

    // ── WS экспортёр ──────────────────────────────────────────────────
    static final class Exporter extends WebSocketClient {

        // req_id -> future с ответом; так коррелируем ответы в многозапросном сценарии
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private int reqIdSeq = 1000;

        Exporter(String wsUrl) {
            super(URI.create(wsUrl));
        }

        @Override public void onOpen(ServerHandshake h) {
            System.out.println("[ws] открыт, статус=" + h.getHttpStatus());
        }

        @Override public void onMessage(String message) {
            try {
                JsonNode n = M.readTree(message);
                int reqId = n.path("req_id").asInt(-1);
                CompletableFuture<JsonNode> fut = (reqId >= 0) ? pending.remove(reqId) : null;
                if (fut != null) {
                    fut.complete(n);
                } else {
                    // не наш коррелированный ответ (ping/pong и пр.) — молча
                    if (n.has("error")) System.err.println("[ws err] " + n.path("error"));
                }
            } catch (Exception e) {
                System.err.println("[ws raw] " + message);
            }
        }

        @Override public void onClose(int code, String reason, boolean remote) {
            System.out.printf("[ws] закрыт code=%d remote=%s reason=%s%n", code, remote, reason);
            // разбудить все висящие запросы, чтобы не зависли навечно
            pending.values().forEach(f -> f.completeExceptionally(
                    new IllegalStateException("ws closed code=" + code)));
            pending.clear();
        }

        @Override public void onError(Exception ex) {
            System.err.println("[ws] error: " + ex.getMessage());
        }

        /** Один постраничный проход по символу от now назад до sinceSec, запись в CSV. */
        void dumpSymbol(String symbol, long endSec, long sinceSec) throws Exception {
            Path file = OUT_DIR.resolve(symbol + "-ticks-" + DAYS + "d-" + RUN_STAMP + ".csv");

            long totalWritten = 0;
            long oldestSeen = Long.MAX_VALUE;

            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("epoch,quote");
                w.newLine();

                long cursorEnd = endSec;

                for (int page = 0; page < MAX_PAGES_PER_SYMBOL; page++) {
                    JsonNode resp = requestHistory(symbol, cursorEnd);

                    if (resp.has("error")) {
                        System.err.println("[" + symbol + "] error: " + resp.path("error"));
                        break;
                    }

                    JsonNode hist = resp.path("history");
                    JsonNode times = hist.path("times");
                    JsonNode prices = hist.path("prices");

                    // Если форма ответа не та, что ждём — показываем ключи и выходим,
                    // чтобы не писать молча пустой файл.
                    if (!times.isArray() || !prices.isArray()) {
                        System.err.println("[" + symbol + "] неожиданная форма ответа, ключи: "
                                + fieldNames(resp) + " / history: " + fieldNames(hist));
                        System.err.println(pretty(resp.toString()).substring(0,
                                Math.min(600, resp.toString().length())));
                        break;
                    }

                    int n = Math.min(times.size(), prices.size());
                    if (n == 0) break;   // истории больше нет

                    // ответ приходит по возрастанию времени: [0] самый старый в странице
                    long pageOldest = times.get(0).asLong();
                    long pageNewest = times.get(n - 1).asLong();

                    // пишем страницу; отбрасываем то, что старше границы
                    int wroteThisPage = 0;
                    for (int i = 0; i < n; i++) {
                        long ep = times.get(i).asLong();
                        if (ep < sinceSec) continue;
                        w.write(ep + "," + prices.get(i).asText());
                        w.newLine();
                        wroteThisPage++;
                    }
                    totalWritten += wroteThisPage;
                    oldestSeen = Math.min(oldestSeen, pageOldest);

                    System.out.printf("[%s] page=%d got=%d wrote=%d oldest=%d newest=%d%n",
                            symbol, page, n, wroteThisPage, pageOldest, pageNewest);

                    // дошли до границы 3 дней — стоп
                    if (pageOldest <= sinceSec) break;

                    // защита от зацикливания: если страница не сдвинула нас назад — стоп
                    long nextEnd = pageOldest - 1;
                    if (nextEnd >= cursorEnd) {
                        System.err.println("[" + symbol + "] курсор не движется назад, стоп");
                        break;
                    }
                    cursorEnd = nextEnd;
                }
            }

            System.out.printf(">>> %s: записано %d тиков, самый старый epoch=%s (граница=%d)%n%n",
                    symbol, totalWritten,
                    (oldestSeen == Long.MAX_VALUE ? "нет" : oldestSeen), sinceSec);
        }

        /** Один запрос ticks_history: [sinceHint..end], count=PAGE, без подписки. */
        private JsonNode requestHistory(String symbol, long endSec) throws Exception {
            int reqId = ++reqIdSeq;
            CompletableFuture<JsonNode> fut = new CompletableFuture<>();
            pending.put(reqId, fut);

            // style=ticks -> в ответе history.times[] / history.prices[]
            // start=1 + end=<epoch> + count=PAGE: сервер вернёт до PAGE тиков, оканчивающихся на end
            String req = String.format(
                    "{\"ticks_history\":\"%s\",\"end\":%d,\"count\":%d,\"style\":\"ticks\",\"req_id\":%d}",
                    symbol, endSec, PAGE, reqId);

            send(req);

            try {
                return fut.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                pending.remove(reqId);
                throw e;
            }
        }
    }

    // ── REST helpers (как в DerivTickTest) ────────────────────────────
    static String restGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(REST_BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Deriv-App-ID", APP_ID)
                .header("Authorization", "Bearer " + PAT)
                .GET().build();
        return send(req);
    }

    static String restPost(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(REST_BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Deriv-App-ID", APP_ID)
                .header("Authorization", "Bearer " + PAT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req);
    }

    static String send(HttpRequest req) throws Exception {
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            System.err.printf("HTTP %d на %s%nтело: %s%n", r.statusCode(), req.uri(), r.body());
        }
        return r.body();
    }

    // ── utils ─────────────────────────────────────────────────────────
    static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    static String redact(String url) {
        return url.replaceAll("([?&]otp=)[^&]+", "$1***");
    }

    static String pretty(String json) {
        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(M.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }
}
