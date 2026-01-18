package com.zoosool.deriv;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the latest known balance.
 * Empty Optional means "balance not received yet".
 */
public final class BalanceHolder {

    private final AtomicReference<Optional<BalanceSnapshot>> ref =
            new AtomicReference<>(Optional.empty());

    public Optional<BalanceSnapshot> get() {
        return ref.get();
    }

    public void update(BalanceSnapshot snapshot) {
        ref.set(Optional.of(Objects.requireNonNull(snapshot, "snapshot")));
    }

    public void clear() {
        ref.set(Optional.empty());
    }

    public boolean isPresent() {
        return ref.get().isPresent();
    }

    public record BalanceSnapshot(
            double balance,
            String currency,
            String loginId,
            Instant receivedAt
    ) { }
}
