package com.zoosool.analyze;

import com.zoosool.deriv.DerivTradingService;
import com.zoosool.model.AnalyzeContainer;

public class NoFilterTradeDecisionMaker implements TradeDecisionMaker {

    public NoFilterTradeDecisionMaker(DerivTradingService trading) {
    }

    @Override
    public void decideAndTrade(String symbol, AnalyzeContainer analyze) {

    }
}
