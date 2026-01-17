package com.zoosool.deriv;

import java.util.Optional;

public class DerivCurrencyHolder {
    private volatile String currency = null;

    public DerivCurrencyHolder() {

    }

    public Optional<String> getCurrency() {
        return Optional.ofNullable(currency);
    }

    public void setCurrency(String currency) {
        if (currency != null) {
            this.currency = currency;
        }
    }
}
