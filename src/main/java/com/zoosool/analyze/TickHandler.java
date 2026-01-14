package com.zoosool.analyze;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

public final class TickHandler {

    private final Consumer<String> log;
    private final TickEventRouterService router;

    public TickHandler(Consumer<String> log, TickEventRouterService router) {
        this.log = Objects.requireNonNull(log, "log");
        this.router = Objects.requireNonNull(router, "router");
    }

    public void onTick(JsonNode message) {
        Objects.requireNonNull(message, "message");

        JsonNode tick = message.path("tick");
        String symbol = textOrNull(tick.get("symbol"));
        double quote = tick.path("quote").asDouble(Double.NaN);

        if (symbol != null && !Double.isNaN(quote)) {
            router.onTick(symbol, quote);
        }
    }

    public void onReconnect(String reason) {
        String r = (reason == null || reason.isBlank()) ? "RECONNECT" : reason;
        log.accept("[" + Instant.now() + "] 🔁 TICK_PIPELINE_RESET reason=" + r);
        router.onResetAll(r);
    }

    private static String textOrNull(JsonNode node) {
        return (node != null && node.isTextual()) ? node.asText() : null;
    }
}
