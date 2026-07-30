package com.zoosool.analyze;

import com.zoosool.model.TickSample;
import com.zoosool.model.TickStatsSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Sends TickStatsSnapshot to TradeDecisionMaker
 * and passes snapshot to UI unchanged.
 *
 * Also keeps per-symbol access to research tick history.
 */
public final class TickDecisionEngineSink implements TickStatsSink, Resetable {

    private final TickStatsSink downstream;
    private final TradeDecisionMaker tradeDecisionMaker;

    private final ConcurrentHashMap<String, Supplier<List<TickSample>>> researchTicksBySymbol =
            new ConcurrentHashMap<>();

    public TickDecisionEngineSink(TickStatsSink downstream, TradeDecisionMaker tradeDecisionMaker) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.tradeDecisionMaker = Objects.requireNonNull(tradeDecisionMaker, "tradeDecisionMaker");
    }

    public void registerResearchTicks(String symbol, Supplier<List<TickSample>> supplier) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(supplier, "supplier");

        researchTicksBySymbol.put(symbol, supplier);
    }

    @Override
    public void onSnapshot(TickStatsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        Supplier<List<TickSample>> researchTicksSupplier =
                researchTicksBySymbol.get(snapshot.symbol());

        if (researchTicksSupplier == null) {
            researchTicksSupplier = List::of;
        }

        tradeDecisionMaker.decideAndTradeSnap(
                snapshot.symbol(),
                snapshot,
                researchTicksSupplier
        );

        downstream.onSnapshot(snapshot);
    }

    @Override
    public void reset() {
        if (downstream instanceof Resetable r) {
            r.reset();
        }
    }

    public void forgetSymbol(String symbol) {
        if (symbol != null) researchTicksBySymbol.remove(symbol);
    }
}