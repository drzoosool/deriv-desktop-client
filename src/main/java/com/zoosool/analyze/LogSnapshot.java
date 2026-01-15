package com.zoosool.analyze;

import com.zoosool.model.TickStatsSnapshot;

import java.util.function.Consumer;

public class LogSnapshot implements TickStatsSink {
    private final Consumer<String> appLogView;

    public LogSnapshot(Consumer<String> appLogView) {
        this.appLogView = appLogView;
    }

    @Override
    public void onSnapshot(TickStatsSnapshot snap) {
        appLogView.accept(
                "[STATS] symbol=" + snap.symbol()
                        + " state=" + snap.state()
                        + " decision=" + snap.decision()
                        + " adlL=" + snap.adlLong()
                        + " adlS=" + snap.adlShort()
                        + " xmaL=" + snap.xmaLong()
                        + " xmaS=" + snap.xmaShort()
                        + " zS=" + snap.zeroShort()
                        + " L=" + snap.longWindow()
                        + " S=" + snap.shortWindow()
                        + " MA=" + snap.maWindow()
                        + " bufL=" + snap.bufLong() + "/" + snap.longWindow()
                        + " bufS=" + snap.bufShort() + "/" + snap.shortWindow()
                        + (snap.reason() != null ? " reason=" + snap.reason() : "")
        );
    }
}
