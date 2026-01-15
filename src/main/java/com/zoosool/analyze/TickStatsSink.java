package com.zoosool.analyze;

import com.zoosool.model.TickStatsSnapshot;

@FunctionalInterface
public interface TickStatsSink {
    void onSnapshot(TickStatsSnapshot snapshot);
}

