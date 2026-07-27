package com.zoosool.analyze;

import com.zoosool.model.TickStatsSnapshot;

import java.util.List;

public final class FanOutTickStatsSink implements TickStatsSink, Resetable {

    private final List<TickStatsSink> sinks;

    public FanOutTickStatsSink(TickStatsSink... sinks) {
        this.sinks = List.of(sinks);
    }

    @Override
    public void onSnapshot(TickStatsSnapshot snapshot) {
        for (TickStatsSink s : sinks) {
            try {
                s.onSnapshot(snapshot);
            } catch (Exception ignore) {
                // один приёмник не должен ронять остальных
            }
        }
    }

    @Override
    public void reset() {
        for (TickStatsSink s : sinks) {
            if (s instanceof Resetable r) {
                try {
                    r.reset();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
