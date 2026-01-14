package com.zoosool.model;

import java.util.List;
import java.util.Objects;

public record TickSubscriptionsBundle(List<TickSubscriptionInfo> subscriptions) {
    public TickSubscriptionsBundle {
        Objects.requireNonNull(subscriptions, "subscriptions");
    }
}
