package com.zoosool.state;

import com.zoosool.model.ActiveSymbol;

public class TradeWindowState {

    private volatile boolean autoTradeEnabled = false;
    private volatile ActiveSymbol selectedAsset = null;
    private volatile String basis = "payout";
    private volatile int duration = 2;

    public boolean isAutoTradeEnabled() {
        return autoTradeEnabled;
    }

    public void setAutoTradeEnabled(boolean autoTradeEnabled) {
        this.autoTradeEnabled = autoTradeEnabled;
    }

    public ActiveSymbol getSelectedAsset() {
        return selectedAsset;
    }

    public void setSelectedAsset(ActiveSymbol selectedAsset) {
        this.selectedAsset = selectedAsset;
    }

    public String getBasis() {
        return basis;
    }

    public void setBasis(String basis) {
        this.basis = basis;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }
}
