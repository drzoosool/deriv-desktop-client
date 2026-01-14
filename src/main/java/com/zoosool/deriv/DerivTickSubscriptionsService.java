package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoosool.model.ActiveSymbol;
import com.zoosool.model.TickSubscriptionInfo;
import com.zoosool.model.TickSubscriptionsBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class DerivTickSubscriptionsService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Consumer<String> log;

    public DerivTickSubscriptionsService(Consumer<String> log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Subscribes to ticks for every provided symbol.
     * Returns bundle containing (symbol -> subscriptionId) mapping for the NEW connection.
     */
    public CompletableFuture<TickSubscriptionsBundle> subscribeAll(DerivWsClient ws, List<ActiveSymbol> symbols) {
        Objects.requireNonNull(ws, "ws");
        Objects.requireNonNull(symbols, "symbols");

        if (symbols.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("symbols is empty"));
        }

        List<CompletableFuture<TickSubscriptionInfo>> futures = new ArrayList<>();

        for (ActiveSymbol s : symbols) {
            String symbol = s.symbol();
            if (symbol == null || symbol.isBlank()) continue;
            futures.add(subscribeOne(ws, symbol));
        }

        if (futures.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No valid symbols to subscribe"));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));

        return all.thenApply(v -> {
            List<TickSubscriptionInfo> out = futures.stream().map(CompletableFuture::join).toList();
            log.accept("✅ Tick subscriptions ready: " + out.size());
            return new TickSubscriptionsBundle(out);
        });
    }

    private CompletableFuture<TickSubscriptionInfo> subscribeOne(DerivWsClient ws, String symbol) {
        ObjectNode req = mapper.createObjectNode();
        req.put("ticks", symbol);
        req.put("subscribe", 1);

        return ws.sendRequest(req).thenApply(resp -> {
            String subId = extractSubscriptionId(resp);
            if (subId == null) {
                // We want to see the real payload, because you asked to understand what comes back.
                throw new IllegalStateException("No subscription.id in tick subscribe response for symbol=" + symbol + " resp=" + resp);
            }
            log.accept("✅ Tick subscribe OK symbol=" + symbol + " subId=" + subId);
            return new TickSubscriptionInfo(symbol, subId);
        }).exceptionally(ex -> {
            log.accept("❌ Tick subscribe FAIL symbol=" + symbol + ": " + rootMessage(ex));
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
