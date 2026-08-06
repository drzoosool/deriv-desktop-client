package com.zoosool.model;

import java.util.List;

public record DerivSession(
        String currency,
        List<ActiveSymbol> activeSymbols
) {
    public List<ActiveSymbol> stepIndices() {
        return activeSymbols.stream()
                .filter(s -> s.symbol().startsWith("stpRNG") || s.symbol().startsWith("1HZ"))
                .toList();
    }
}
