package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DerivWsClient extends WebSocketClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger reqSeq = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final TickHandler tickHandler;

    private final String token;
    private final Consumer<String> log;

    private final CompletableFuture<JsonNode> authorizeResp = new CompletableFuture<>();

    /**
     * Optional callback used by a higher-level connector to react on socket close/error.
     * First argument is a short "where" marker, second is the exception (may be null for close).
     */
    private volatile BiConsumer<String, Exception> disconnectListener;

    public DerivWsClient(URI serverUri, String token, Consumer<String> log, TickHandler tickHandler) {
        super(serverUri);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Deriv token must not be blank");
        }
        this.token = token;
        this.log = (log != null) ? log : System.out::println;
        this.tickHandler = tickHandler;
    }

    public void setDisconnectListener(BiConsumer<String, Exception> disconnectListener) {
        this.disconnectListener = disconnectListener;
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
                    log("✅ Authorized: " + compactJson(resp));
                    authorizeResp.complete(resp);
                })
                .exceptionally(ex -> {
                    log("❌ Authorization failed: " + rootMessage(ex));
                    authorizeResp.completeExceptionally(ex);
                    fireDisconnect("authorize", unwrapException(ex));
                    return null;
                });
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode node = mapper.readTree(message);
            String msgType = node.path("msg_type").asText("");

            // 1) Complete pending request futures (req_id correlation)
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

            // 2) Handle pushes
            if ("pong".equals(msgType)) {
                // Usually too noisy; ignore by default.
                return;
            }

            if ("tick".equals(msgType)) {
                tickHandler.onTick(node);
                return;
            }

            // 3) Log other push messages to understand the stream
            if (!msgType.isBlank()) {
                log("📩 PUSH msg_type=" + msgType + " raw=" + compactJson(node));
            } else {
                log("📩 PUSH without msg_type raw=" + compactJson(node));
            }

        } catch (Exception ex) {
            log("❗ parse error: " + ex.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log("❌ WS closed: code=" + code + ", reason=" + reason + ", remote=" + remote);

        // Fail all pending requests
        RuntimeException ex = new RuntimeException("Socket closed: " + reason);
        pending.forEach((k, f) -> f.completeExceptionally(ex));
        pending.clear();

        if (!authorizeResp.isDone()) {
            authorizeResp.completeExceptionally(ex);
        }

        fireDisconnect("close", ex);
    }

    @Override
    public void onError(Exception ex) {
        log("❗ WS error: " + (ex != null ? ex.getMessage() : "null"));
        fireDisconnect("error", ex);
    }

    /**
     * Sends a request with generated req_id and returns a future for its response.
     * If the server responds with "error", the future completes exceptionally.
     */
    public CompletableFuture<JsonNode> sendRequest(ObjectNode req) {
        Objects.requireNonNull(req, "req");

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

    private void fireDisconnect(String where, Exception ex) {
        BiConsumer<String, Exception> l = disconnectListener;
        if (l == null) return;

        try {
            l.accept(where, ex);
        } catch (Exception ignore) {
            // Never let listener exceptions kill the WS thread.
        }
    }

    private void log(String s) {
        log.accept("[" + Instant.now() + "] " + s);
    }

    private String compactJson(JsonNode node) {
        try {
            // Ensures single-line JSON (no pretty print)
            return mapper.writeValueAsString(node);
        } catch (Exception ex) {
            // Fallback: JsonNode#toString is typically already compact.
            return String.valueOf(node);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    private static Exception unwrapException(Throwable t) {
        if (t == null) return null;
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return (cur instanceof Exception) ? (Exception) cur : new RuntimeException(cur);
    }
}
