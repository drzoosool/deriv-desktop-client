package com.zoosool.model;

import java.util.List;

public record DerivSession(
        String currency,
        List<ActiveSymbol> activeSymbols
) {
    public List<ActiveSymbol> stepIndices() {
        return activeSymbols.stream()
                .filter(s -> s.displayName().toLowerCase(java.util.Locale.ROOT).contains("step index"))
                .toList();
    }
}
