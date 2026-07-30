package com.zoosool.analyze;

import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.TickSample;
import com.zoosool.model.TickStatsSnapshot;

import java.util.List;
import java.util.function.Supplier;

/**
 * Decides whether to place an auto-trade based on current snapshot + per-symbol analysis state.
 * Side-effecting: may trigger actual trading. No return value by design.
 */
public interface TradeDecisionMaker {

    default void decideAndTradeSnap(String symbol, TickStatsSnapshot snapshot, Supplier<List<TickSample>> researchTicksSupplier) {
        return;
    }
}
