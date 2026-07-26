package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoosool.analyze.TickHandler;
import com.zoosool.config.DerivAppConfig;
import com.zoosool.model.DerivConnection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

public class DerivWebSocketClientFactory {
    static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static final String REST_BASE = "https://api.derivws.com/trading/v1/options";

    public static DerivConnection getClient(DerivAppConfig cfg,
                                            Consumer<String> uiLog,
                                            TickHandler tickHandler,
                                            BalanceHandler balanceHandler) throws Exception {
        try {
            Objects.requireNonNull(cfg, "cfg");
            if (cfg.derivToken() == null || cfg.derivToken().isBlank()) {
                throw new IllegalArgumentException("deriv.app.token is blank in config.deriv.properties");
            }
            if (cfg.derivAppId() == null) {
                throw new IllegalArgumentException("deriv.app.id is blank in config.deriv.properties");
            }

            // ── ФАЗА 1: список счетов ────────────────────────────────────
            System.out.println(">>> GET /accounts");
            String accountsRaw = restGet("/accounts", cfg);
            System.out.println("RAW accounts:\n" + pretty(accountsRaw) + "\n");

            JsonNode accounts = M.readTree(accountsRaw).path("data");
            if (!accounts.isArray() || accounts.isEmpty()) {
                System.err.println("data пустой или не массив — смотри RAW выше, форма могла отличаться.");
                throw new IllegalArgumentException("Accaounts is empty");
            }

            // ── выбор демо-счёта строго по полю, без эвристик по префиксу ──
            JsonNode demo = null;
            for (JsonNode a : accounts) {
                String type = a.path("account_type").asText("");
                String status = a.path("status").asText("");
                if (type.equalsIgnoreCase("demo") && status.equalsIgnoreCase("active")) {
                    demo = a;
                    break;
                }
            }
            if (demo == null) {
                throw new IllegalStateException("Активный demo-счёт не найден. Проверь account_type в RAW выше.");
            }
            String accountId = demo.path("account_id").asText();
            String currency = demo.path("currency").asText();
            System.out.printf(">>> demo: account_id=%s currency=%s%n%n", accountId, currency);

            // ── ФАЗА 2: OTP -> URL сокета ────────────────────────────────
            System.out.println(">>> POST /accounts/" + accountId + "/otp");
            String otpRaw = restPost("/accounts/" + accountId + "/otp", "", cfg);
            System.out.println("RAW otp:\n" + pretty(otpRaw) + "\n");

            String wsUrl = M.readTree(otpRaw).path("data").path("url").asText(null);
            if (wsUrl == null || wsUrl.isBlank()) {
                throw new IllegalStateException("В ответе нет data.url — смотри RAW выше.");
            }
            // проверка окружения по сегменту пути (см. redact в логе)
            if (!wsUrl.contains("/ws/demo")) {
                throw new IllegalStateException("URL не похож на demo-сокет: " + redact(wsUrl));
            }
            System.out.println(">>> ws url (otp скрыт): " + redact(wsUrl) + "\n");

            DerivWsClient derivWsClient = new DerivWsClient(URI.create(wsUrl), cfg.derivToken(), uiLog, tickHandler, balanceHandler);
            return new DerivConnection(derivWsClient, currency, accountId);
        } catch (Exception e) {
            uiLog.accept("Error when connect to Deriv: " + e.getMessage());
            throw e;
        }
    }

    // ── REST helpers ─────────────────────────────────────────────────
    static String restGet(String path, DerivAppConfig cfg) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(REST_BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Deriv-App-ID", cfg.derivAppId())
                .header("Authorization", "Bearer " + cfg.derivToken())
                .GET().build();
        return send(req);
    }

    static String restPost(String path, String body, DerivAppConfig cfg) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(REST_BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Deriv-App-ID", cfg.derivAppId())
                .header("Authorization", "Bearer " + cfg.derivToken())
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

    static String redact(String url) {
        return url.replaceAll("([?&]otp=)[^&]+", "$1***");
    }
    static String pretty(String json) {
        try { return M.writerWithDefaultPrettyPrinter()
                .writeValueAsString(M.readTree(json)); }
        catch (Exception e) { return json; }
    }
}
