package com.zoosool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Тестовый прогон цепочки Deriv Options API (новый контур):
 *   PAT -> GET /accounts -> выбрать demo -> POST /otp -> WS -> subscribe ticks.
 *
 * ЗАПУСК: обычный main, JavaFX не нужен.
 * ВНИМАНИЕ: PAT захардкожен только для теста. Не коммитить. Скоуп: trade.
 */
public class DerivTickTest {

    // ── КОНФИГ (для теста хардкод; потом в vault/env) ─────────────────
    static final String APP_ID = "id";          // из developers.deriv.com
    static final String PAT     = "pass";          // Deriv → API token, scope trade
    static final String REST_BASE = "https://api.derivws.com/trading/v1/options";
    static final String SYMBOL = "R_100";                // синтетика, торгуется 24/7

    static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        if (APP_ID.startsWith("REPLACE") || PAT.startsWith("REPLACE")) {
            System.err.println("Впиши APP_ID и PAT в начале файла.");
            return;
        }

        // ── ФАЗА 1: список счетов ────────────────────────────────────
        System.out.println(">>> GET /accounts");
        String accountsRaw = restGet("/accounts");
        System.out.println("RAW accounts:\n" + pretty(accountsRaw) + "\n");

        JsonNode accounts = M.readTree(accountsRaw).path("data");
        if (!accounts.isArray() || accounts.isEmpty()) {
            System.err.println("data пустой или не массив — смотри RAW выше, форма могла отличаться.");
            return;
        }

        // ── выбор демо-счёта строго по полю, без эвристик по префиксу ──
        JsonNode demo = null;
        for (JsonNode a : accounts) {
            String type = a.path("account_type").asText("");
            String status = a.path("status").asText("");
            if (type.equalsIgnoreCase("demo") && status.equalsIgnoreCase("active")) {
                demo = a; break;
            }
        }
        if (demo == null) {
            System.err.println("Активный demo-счёт не найден. Проверь account_type в RAW выше.");
            return;
        }
        String accountId = demo.path("account_id").asText();
        String currency  = demo.path("currency").asText();
        System.out.printf(">>> demo: account_id=%s currency=%s%n%n", accountId, currency);

        // ── ФАЗА 2: OTP -> URL сокета ────────────────────────────────
        System.out.println(">>> POST /accounts/" + accountId + "/otp");
        String otpRaw = restPost("/accounts/" + accountId + "/otp", "");
        System.out.println("RAW otp:\n" + pretty(otpRaw) + "\n");

        String wsUrl = M.readTree(otpRaw).path("data").path("url").asText(null);
        if (wsUrl == null || wsUrl.isBlank()) {
            System.err.println("В ответе нет data.url — смотри RAW выше.");
            return;
        }
        // проверка окружения по сегменту пути (см. redact в логе)
        if (!wsUrl.contains("/ws/demo")) {
            System.err.println("URL не похож на demo-сокет: " + redact(wsUrl));
            return;
        }
        System.out.println(">>> ws url (otp скрыт): " + redact(wsUrl) + "\n");

        // ── ФАЗА 3: сокет + подписка ─────────────────────────────────
        connectAndStream(wsUrl);
    }

    // ── REST helpers ─────────────────────────────────────────────────
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
            // не глотаем тело: там errors[].code/message — самое ценное для диагностики
            System.err.printf("HTTP %d на %s%nтело: %s%n", r.statusCode(), req.uri(), r.body());
        }
        return r.body();
    }

    // ── WebSocket ────────────────────────────────────────────────────
    static void connectAndStream(String wsUrl) throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger reqId = new AtomicInteger(100);

        WebSocketClient client = new WebSocketClient(URI.create(wsUrl)) {
            @Override public void onOpen(ServerHandshake h) {
                System.out.println("[ws] открыт, статус=" + h.getHttpStatus());
                // подписка на тики. echo_req опционален -> коррелируем по req_id
                String sub = String.format(
                        "{\"ticks\":\"%s\",\"subscribe\":1,\"req_id\":%d}",
                        SYMBOL, reqId.getAndIncrement());
                System.out.println("[ws] -> " + sub);
                send(sub);
            }

            @Override public void onMessage(String message) {
                // Java-WebSocket склеивает фрагменты сам -> тут целое сообщение
                try {
                    JsonNode n = M.readTree(message);
                    String type = n.path("msg_type").asText("");
                    if ("tick".equals(type)) {
                        JsonNode t = n.path("tick");
                        System.out.printf("[tick] %s  quote=%s  epoch=%s%n",
                                t.path("symbol").asText(),
                                t.path("quote").asText(),
                                t.path("epoch").asText());
                    } else if (n.has("error")) {
                        System.err.println("[ws err] " + n.path("error").toString());
                    } else {
                        System.out.println("[ws msg] " + type + " :: " + message);
                    }
                } catch (Exception e) {
                    System.out.println("[ws raw] " + message);
                }
            }

            @Override public void onClose(int code, String reason, boolean remote) {
                // код важнее факта: 1006 = сеть/TLS, не авторизация
                System.out.printf("[ws] закрыт code=%d remote=%s reason=%s%n",
                        code, remote, reason);
                closed.countDown();
            }

            @Override public void onError(Exception ex) {
                System.err.println("[ws] error: " + ex.getMessage());
            }
        };

        System.out.println("[ws] подключаюсь...");
        client.connectBlocking();          // блокирующе — удобно для теста

        // keepalive: в legacy простой >2 мин рвал коннект. Проверяем, живо ли в новом.
        Thread ping = new Thread(() -> {
            try {
                while (!closed.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (client.isOpen()) client.send("{\"ping\":1}");
                }
            } catch (InterruptedException ignored) {}
        });
        ping.setDaemon(true);
        ping.start();

        closed.await();                    // держим main живым, пока сокет открыт
        System.out.println("Готово.");
    }

    // ── utils ────────────────────────────────────────────────────────
    static String redact(String url) {
        return url.replaceAll("([?&]otp=)[^&]+", "$1***");
    }
    static String pretty(String json) {
        try { return M.writerWithDefaultPrettyPrinter()
                .writeValueAsString(M.readTree(json)); }
        catch (Exception e) { return json; }
    }
}