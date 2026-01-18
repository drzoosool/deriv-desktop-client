package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

public final class BalanceHandler {

    private final BalanceHolder holder;
    private final Consumer<String> log;

    // Reduce log spam: log only on meaningful change
    private volatile Double lastLoggedAmount = null;
    private static final double EPS = 1e-9;

    public BalanceHandler(BalanceHolder holder, Consumer<String> log) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.log = Objects.requireNonNull(log, "log");
    }

    public void onBalance(JsonNode node) {
        JsonNode b = node.path("balance");
        if (b.isMissingNode() || b.isNull()) {
            return;
        }

        double amount = b.path("balance").asDouble(Double.NaN);
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return;
        }

        String currency = b.path("currency").isTextual() ? b.path("currency").asText() : null;
        String loginId = b.path("loginid").isTextual() ? b.path("loginid").asText() : null;

        BalanceHolder.BalanceSnapshot snap = new BalanceHolder.BalanceSnapshot(
                amount,
                currency,
                loginId,
                Instant.now()
        );

        holder.update(snap);

        // Log only if changed (or first time)
        Double prev = lastLoggedAmount;
        if (prev == null || Math.abs(amount - prev) > EPS) {
            lastLoggedAmount = amount;
            String cur = (currency == null || currency.isBlank()) ? "" : (" " + currency);
            String acc = (loginId == null || loginId.isBlank()) ? "" : (" loginId=" + loginId);
            log.accept("💰 BALANCE: " + amount + cur + acc);
        }
    }
}
