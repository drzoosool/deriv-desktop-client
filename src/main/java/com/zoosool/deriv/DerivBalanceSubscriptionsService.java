package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoosool.model.BalanceSubscriptionInfo;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Subscribes to Deriv balance stream for the current authorized connection.
 */
public final class DerivBalanceSubscriptionsService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Consumer<String> log;

    public DerivBalanceSubscriptionsService(Consumer<String> log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Subscribes to balance updates (stream).
     * Deriv request: {"balance": 1, "subscribe": 1}
     */
    public CompletableFuture<BalanceSubscriptionInfo> subscribe(DerivWsClient ws) {
        Objects.requireNonNull(ws, "ws");

        ObjectNode req = mapper.createObjectNode();
        req.put("balance", 1);
        req.put("subscribe", 1);

        return ws.sendRequest(req).thenApply(resp -> {
            String subId = extractSubscriptionId(resp);
            if (subId == null) {
                throw new IllegalStateException("No subscription.id in balance subscribe response resp=" + resp);
            }

            // Optional: log initial balance fields if present
            JsonNode b = resp.path("balance");
            if (!b.isMissingNode()) {
                String currency = b.path("currency").isTextual() ? b.path("currency").asText() : "?";
                double amount = b.path("balance").asDouble(Double.NaN);
                log.accept("✅ Balance subscribe OK subId=" + subId + " currency=" + currency + " balance=" + amount);
            } else {
                log.accept("✅ Balance subscribe OK subId=" + subId);
            }

            return new BalanceSubscriptionInfo(subId);
        }).exceptionally(ex -> {
            log.accept("❌ Balance subscribe FAIL: " + rootMessage(ex));
            throw new RuntimeException(ex);
        });
    }

    private static String extractSubscriptionId(JsonNode resp) {
        JsonNode subId = resp.path("subscription").path("id");
        return subId.isTextual() ? subId.asText() : null;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }
}
