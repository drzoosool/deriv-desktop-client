package com.zoosool.model;

import com.zoosool.enums.TickAction;

import java.time.Instant;
import java.util.Objects;

/**
 * A normalized event sent to per-symbol queues.
 * For now: quote + action. Later you can add epoch/subscriptionId/etc.
 */
public record TickEvent(
        String symbol,
        TickAction action,
        String quote,
        Instant receivedAt
) {
    public TickEvent {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public static TickEvent tick(String symbol, String quote) {
        return new TickEvent(symbol, TickAction.TICK, quote, Instant.now());
    }

    public static TickEvent reset(String symbol) {
        return new TickEvent(symbol, TickAction.RESET, null, Instant.now());
    }

    public static TickEvent stop(String symbol) {
        return new TickEvent(symbol, TickAction.STOP, null, Instant.now());
    }
}
