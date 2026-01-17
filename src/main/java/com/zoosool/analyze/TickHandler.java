package com.zoosool.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoosool.utils.NumberStringUtils;

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
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        JsonNode quoteNode = tick.get("quote");
        if (quoteNode == null || quoteNode.isNull()) {
            return;
        }

        // Take quote as raw text to avoid double precision artifacts.
        String quoteText = quoteNode.asText(null);
        if (quoteText == null || quoteText.isBlank()) {
            return;
        }

        router.onTick(symbol, quoteText);
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
