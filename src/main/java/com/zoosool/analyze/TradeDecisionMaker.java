package com.zoosool.analyze;

import com.zoosool.model.AnalyzeContainer;

/**
 * Decides whether to place an auto-trade based on current snapshot + per-symbol analysis state.
 * Side-effecting: may trigger actual trading. No return value by design.
 */
public interface TradeDecisionMaker {

    void decideAndTrade(String symbol, AnalyzeContainer analyze);
}
