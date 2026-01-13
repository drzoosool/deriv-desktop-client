package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class DerivWsClient extends WebSocketClient {

    private static final int PING_PERIOD_SECONDS = 20;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger reqSeq = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private final String token;
    private final Consumer<String> log;

    private final CompletableFuture<JsonNode> authorizeResp = new CompletableFuture<>();

    private ScheduledExecutorService pingScheduler;

    // Tick subscription state (MVP)
    private volatile String step100Symbol;
    private volatile String tickSubscriptionId;

    public DerivWsClient(URI serverUri, String token, Consumer<String> log) {
        super(serverUri);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Deriv token must not be blank");
        }
        this.token = token;
        this.log = (log != null) ? log : System.out::println;
    }

    public CompletableFuture<JsonNode> authorized() {
        return authorizeResp;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log("🔌 WS connected, authorizing...");

        ObjectNode auth = mapper.createObjectNode();
        auth.put("authorize", token);

        sendRequest(auth)
                .thenAccept(resp -> {
                    log("✅ Authorized");
                    authorizeResp.complete(resp);

                    startPing();
                    //subscribeStepIndex100Ticks();
                })
                .exceptionally(ex -> {
                    log("❌ Authorization failed: " + ex.getMessage());
                    authorizeResp.completeExceptionally(ex);
                    return null;
                });
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode node = mapper.readTree(message);

            String msgType = node.path("msg_type").asText("");

            JsonNode reqIdNode = node.get("req_id");
            if (reqIdNode != null && reqIdNode.isInt()) {
                int reqId = reqIdNode.asInt();
                CompletableFuture<JsonNode> f = pending.remove(reqId);
                if (f != null) {
                    if (node.has("error")) {
                        f.completeExceptionally(new RuntimeException(node.get("error").toString()));
                    } else {
                        f.complete(node);
                    }
                }
            }

            if ("pong".equals(msgType)) {
                return;
            }

            if ("tick".equals(msgType)) {
                handleTick(node);
                return;
            }

            if (!msgType.isBlank()) {
                log("📩 msg_type=" + msgType);
            } else {
                log("📩 push message without msg_type");
            }

        } catch (Exception ex) {
            log("❗ parse error: " + ex.getMessage());
        }
    }

    private void handleTick(JsonNode node) {
        JsonNode tick = node.path("tick");
        double quote = tick.path("quote").asDouble(Double.NaN);
        long epoch = tick.path("epoch").asLong(0);

        JsonNode subId = node.path("subscription").path("id");
        if (subId.isTextual()) {
            tickSubscriptionId = subId.asText();
        }

        log("📈 TICK " + (step100Symbol != null ? step100Symbol : "?")
                + " quote=" + quote
                + " epoch=" + epoch
                + (tickSubscriptionId != null ? " subId=" + tickSubscriptionId : ""));
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log("❌ WS closed: code=" + code + ", reason=" + reason + ", remote=" + remote);
        stopPing();

        // fail all pending requests
        RuntimeException ex = new RuntimeException("Socket closed: " + reason);
        pending.forEach((k, f) -> f.completeExceptionally(ex));
        pending.clear();

        if (!authorizeResp.isDone()) {
            authorizeResp.completeExceptionally(ex);
        }
    }

    @Override
    public void onError(Exception ex) {
        log("❗ WS error: " + ex.getMessage());
    }

    /**
     * Sends a request with generated req_id and returns a future for its response.
     * If the server responds with "error", the future completes exceptionally.
     */
    public CompletableFuture<JsonNode> sendRequest(ObjectNode req) {
        int reqId = reqSeq.getAndIncrement();
        req.put("req_id", reqId);

        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(reqId, f);

        try {
            send(req.toString());
        } catch (Exception ex) {
            pending.remove(reqId);
            f.completeExceptionally(ex);
        }

        return f;
    }

    /**
     * MVP: detect symbol for Step Index 100 and subscribe to its ticks.
     */
    private void subscribeStepIndex100Ticks() {
        // 1) find symbol via active_symbols
        ObjectNode req = mapper.createObjectNode();
        req.put("active_symbols", "brief");
        req.put("product_type", "basic");

        sendRequest(req)
                .thenApply(this::findStepIndex100Symbol)
                .thenCompose(symbol -> {
                    this.step100Symbol = symbol;
                    log("🔎 Step Index 100 symbol resolved: " + symbol);

                    // 2) subscribe to ticks
                    ObjectNode ticks = mapper.createObjectNode();
                    ticks.put("ticks", symbol);
                    ticks.put("subscribe", 1);

                    return sendRequest(ticks);
                })
                .thenAccept(resp -> {
                    // initial subscription response usually contains tick + subscription.id
                    JsonNode subId = resp.path("subscription").path("id");
                    if (subId.isTextual()) {
                        tickSubscriptionId = subId.asText();
                    }
                    log("✅ Tick subscription started"
                            + (tickSubscriptionId != null ? " subId=" + tickSubscriptionId : ""));
                })
                .exceptionally(ex -> {
                    log("❌ Tick subscribe failed: " + ex.getMessage());
                    return null;
                });
    }

    private String findStepIndex100Symbol(JsonNode resp) {
        JsonNode list = resp.path("active_symbols");
        if (!list.isArray()) {
            throw new IllegalStateException("active_symbols not array: " + resp);
        }

        for (JsonNode s : list) {
            String display = s.path("display_name").asText("");
            String displayL = display.toLowerCase(Locale.ROOT);
            if (displayL.contains("step index") && displayL.contains("100")) {
                String sym = s.path("symbol").asText(null);
                if (sym != null && !sym.isBlank()) return sym;
            }
        }

        throw new IllegalStateException("Cannot find Step Index 100 in active_symbols");
    }

    private void startPing() {
        if (pingScheduler != null) return;

        pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deriv-ws-ping");
            t.setDaemon(true);
            return t;
        });

        pingScheduler.scheduleAtFixedRate(() -> {
            try {
                ObjectNode ping = mapper.createObjectNode();
                ping.put("ping", 1);
                send(ping.toString());
            } catch (Exception ignored) {
                // ignore; onError/onClose will handle broken connection
            }
        }, PING_PERIOD_SECONDS, PING_PERIOD_SECONDS, TimeUnit.SECONDS);

        log("🫀 Ping started (" + PING_PERIOD_SECONDS + "s)");
    }

    private void stopPing() {
        if (pingScheduler != null) {
            pingScheduler.shutdownNow();
            pingScheduler = null;
            log("🫀 Ping stopped");
        }
    }

    private void log(String s) {
        log.accept("[" + Instant.now() + "] " + s);
    }
}
