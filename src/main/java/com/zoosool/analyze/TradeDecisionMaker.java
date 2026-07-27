package com.zoosool.analyze;

import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.TickStatsSnapshot;

/**
 * Decides whether to place an auto-trade based on current snapshot + per-symbol analysis state.
 * Side-effecting: may trigger actual trading. No return value by design.
 */
public interface TradeDecisionMaker {

    void decideAndTrade(String symbol, AnalyzeContainer analyze);

    default void decideAndTradeSnap(String symbol, TickStatsSnapshot snapshot) {
        return;
    }
}
