package com.zoosool.model;

import java.util.Objects;

public record TickSubscriptionInfo(String symbol, String subscriptionId) {
    public TickSubscriptionInfo {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(subscriptionId, "subscriptionId");
    }
}
