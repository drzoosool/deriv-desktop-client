package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

public final class TickHandler {

    private final Consumer<String> log;

    public TickHandler(Consumer<String> log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Accepts a raw Deriv WS "tick" message.
     * For now we only log a compact, information-dense line.
     */
    public void onTick(JsonNode message) {
        Objects.requireNonNull(message, "message");

        JsonNode tick = message.path("tick");

        String symbol = textOrNull(tick.get("symbol"));
        long epoch = tick.path("epoch").asLong(0);

        double quote = tick.path("quote").asDouble(Double.NaN);
        double bid = tick.path("bid").asDouble(Double.NaN);
        double ask = tick.path("ask").asDouble(Double.NaN);

        int pipSize = tick.path("pip_size").asInt(-1);

        String subId = textOrNull(message.path("subscription").get("id"));
        String tickId = textOrNull(tick.get("id"));

        // Raw payload is useful now; later we can add a flag to reduce spam.
        String raw = compactJson(message);

        log.accept("[" + Instant.now() + "] 📈 TICK"
                + (symbol != null ? " symbol=" + symbol : "")
                + (epoch > 0 ? " epoch=" + epoch : "")
                + (!Double.isNaN(quote) ? " quote=" + quote : "")
                + (!Double.isNaN(bid) ? " bid=" + bid : "")
                + (!Double.isNaN(ask) ? " ask=" + ask : "")
                + (pipSize >= 0 ? " pip=" + pipSize : "")
                + (tickId != null ? " tickId=" + tickId : "")
                + (subId != null ? " subId=" + subId : "")
                + " raw=" + raw
        );
    }

    private static String textOrNull(JsonNode node) {
        return (node != null && node.isTextual()) ? node.asText() : null;
    }

    private static String compactJson(JsonNode node) {
        // JsonNode#toString обычно уже compact; не тянем сюда ObjectMapper специально.
        return node.toString();
    }
}
