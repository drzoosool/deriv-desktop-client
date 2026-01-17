package com.zoosool.analyze;

import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.TickStatsSnapshot;
import com.zoosool.utils.NumberStringUtils;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extension point for decision/analysis experiments.
 *
 * Current responsibility:
 * - Maintain per-symbol ring buffers (last N):
 *   - price levels (as long, derived from original quote text)
 *   - xmaShort (as long)
 *   - adlShort (scaled to long)
 * - Once levels buffer is full, try to infer tick step (delta between levels).
 * - Forward snapshots downstream without modification (pass-through).
 *
 * Important:
 * - AnalyzeContainer is created ONLY when there is at least one non-null metric to store.
 *   This avoids creating empty containers on RESET snapshots.
 */
public final class TickDecisionEngineSink implements TickStatsSink, Resetable {

    private static final int DEFAULT_WINDOW = 10;

    private final TickStatsSink downstream;
    private final TradeDecisionMaker tradeDecisionMaker;
    private final ConcurrentHashMap<String, AnalyzeContainer> bySymbol = new ConcurrentHashMap<>();

    public TickDecisionEngineSink(TickStatsSink downstream, TradeDecisionMaker tradeDecisionMaker) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.tradeDecisionMaker = tradeDecisionMaker;
    }

    @Override
    public void onSnapshot(TickStatsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        // Read values first. If all are missing -> do not create per-symbol state.
        String quoteString = snapshot.lastQuoteString();
        Integer xmaS = snapshot.xmaShort();
        Double adlS = snapshot.adlShort();

        boolean hasQuote = quoteString != null && !quoteString.isBlank();
        boolean hasXma = xmaS != null;
        boolean hasAdl = adlS != null && !adlS.isNaN() && !adlS.isInfinite();

        AnalyzeContainer st = null;
        if (hasQuote || hasXma || hasAdl) {
            st = bySymbol.computeIfAbsent(
                    snapshot.symbol(),
                    s -> new AnalyzeContainer(DEFAULT_WINDOW)
            );

            // 1) price level buffer (from original quote text)
            if (hasQuote) {
                try {
                    long level = NumberStringUtils.toLongByConcatDroppingTrailingZeros(quoteString);
                    st.onLevel(level);
                } catch (RuntimeException ignoreBadQuote) {
                    // Skip malformed quote formats; do not poison the state.
                }
            }

            // 2) short-window MA crossings buffer
            if (hasXma) {
                st.onXmaShort(xmaS);
            }

            // 3) short-window ADL buffer (scaled)
            if (hasAdl) {
                st.onAdlShort(adlS);
            }
        }

        if (st != null) {
            tradeDecisionMaker.decideAndTrade(snapshot.symbol(), st);
        }

        // Pass-through to UI unchanged
        downstream.onSnapshot(snapshot);
    }

    @Override
    public void reset() {
        try {
            if (downstream instanceof Resetable r) {
                r.reset();
            }
        } finally {
            bySymbol.clear();
        }
    }

    public void forgetSymbol(String symbol) {
        if (symbol != null) bySymbol.remove(symbol);
    }
}
