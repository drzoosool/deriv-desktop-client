package com.zoosool.model;

import com.zoosool.enums.TickDecision;
import com.zoosool.enums.TickStatsState;

import java.time.Instant;
import java.util.Objects;

public record TickStatsSnapshot(
        String symbol,

        TickStatsState state,
        TickDecision decision,

        int longWindow,   // L
        int shortWindow,  // S
        int maWindow,     // MA

        int bufLong,      // filled samples for L
        int bufShort,     // filled samples for S

        Double adlLong,   // null means NA
        Double adlShort,  // null means NA

        Integer xmaLong,  // null means NA
        Integer xmaShort, // null means NA

        Double lastQuote, // last observed quote (nullable on RESET/WARMUP)
        String lastQuoteString,

        int zeroShort,    // zS

        String reason,    // optional: ban/reset reason
        Instant at        // snapshot time
) {
    public TickStatsSnapshot {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(at, "at");
    }
}
