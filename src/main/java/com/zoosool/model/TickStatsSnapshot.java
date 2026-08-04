package com.zoosool.model;

import com.zoosool.enums.TickDecision;
import com.zoosool.enums.TickStatsState;

import java.time.Instant;
import java.util.Map;
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

        Integer ma50ExhaustionScore, // null until MA50 cross age >= 40 sec
        Long secondsSinceMa50Cross,  // null until first MA50 crossing

        String reason,    // optional: ban/reset reason
        Instant at,       // snapshot time

        Map<Integer, MaPoint> movingAverages
) {
    public TickStatsSnapshot {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(at, "at");
    }

    // Старый конструктор оставляем, чтобы существующие вызовы не ломать.
    public TickStatsSnapshot(
            String symbol,
            TickStatsState state,
            TickDecision decision,
            int longWindow,
            int shortWindow,
            int maWindow,
            int bufLong,
            int bufShort,
            Double adlLong,
            Double adlShort,
            Integer xmaLong,
            Integer xmaShort,
            Double lastQuote,
            String lastQuoteString,
            int zeroShort,
            String reason,
            Instant at,
            Map<Integer, MaPoint> movingAverages
    ) {
        this(
                symbol,
                state,
                decision,
                longWindow,
                shortWindow,
                maWindow,
                bufLong,
                bufShort,
                adlLong,
                adlShort,
                xmaLong,
                xmaShort,
                lastQuote,
                lastQuoteString,
                zeroShort,
                null,
                null,
                reason,
                at,
                movingAverages
        );
    }
}