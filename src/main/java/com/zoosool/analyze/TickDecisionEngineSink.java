package com.zoosool.analyze;

import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.TickStatsSnapshot;
import com.zoosool.utils.NumberStringUtils;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains per-symbol AnalyzeContainer:
 * - levels (from lastQuoteString)
 * - xmaShort/xmaLong
 * - adlShort/adlLong (scaled)
 * - zeroShort
 *
 * Then calls TradeDecisionMaker with the container.
 * Pass-through snapshots to UI unchanged.
 */
public final class TickDecisionEngineSink implements TickStatsSink, Resetable {

    private static final int DEFAULT_WINDOW = 14;

    private final TickStatsSink downstream;
    private final TradeDecisionMaker tradeDecisionMaker;
    private final ConcurrentHashMap<String, AnalyzeContainer> bySymbol = new ConcurrentHashMap<>();

    public TickDecisionEngineSink(TickStatsSink downstream, TradeDecisionMaker tradeDecisionMaker) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.tradeDecisionMaker = Objects.requireNonNull(tradeDecisionMaker, "tradeDecisionMaker");
    }

    @Override
    public void onSnapshot(TickStatsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        String symbol = snapshot.symbol();

        String quoteString = snapshot.lastQuoteString();
        Integer xmaS = snapshot.xmaShort();
        Integer xmaL = snapshot.xmaLong();
        Double adlS = snapshot.adlShort();
        Double adlL = snapshot.adlLong();
        int zS = snapshot.zeroShort();

        boolean hasQuote = quoteString != null && !quoteString.isBlank();
        boolean hasXmaS = xmaS != null;
        boolean hasXmaL = xmaL != null;
        boolean hasAdlS = adlS != null && !adlS.isNaN() && !adlS.isInfinite();
        boolean hasAdlL = adlL != null && !adlL.isNaN() && !adlL.isInfinite();

        // We want container if ANY useful data can be stored.
        // zeroShort is always available => we can always store it (cheap) but avoid creating on RESET if you want.
        boolean shouldCreate =
                hasQuote || hasXmaS || hasXmaL || hasAdlS || hasAdlL;

        AnalyzeContainer st = null;
        if (shouldCreate) {
            st = bySymbol.computeIfAbsent(symbol, s -> new AnalyzeContainer(DEFAULT_WINDOW));

            st.onSnapshotAt(snapshot.at());

            if (hasQuote) {
                try {
                    long level = NumberStringUtils.toScaledLong(quoteString, 4);
                    st.onLevel(level);
                } catch (RuntimeException ignoreBadQuote) {
                    // Skip malformed quote formats.
                }
            }

            if (hasXmaS) st.onXmaShort(xmaS);
            if (hasXmaL) st.onXmaLong(xmaL);

            if (hasAdlS) st.onAdlShort(adlS);
            if (hasAdlL) st.onAdlLong(adlL);

            // Even if other metrics exist, store zS as well.
            st.onZeroShort(zS);
        }

        if (st != null) {
            tradeDecisionMaker.decideAndTrade(symbol, st);
        }

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
