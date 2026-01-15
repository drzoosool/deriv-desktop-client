package com.zoosool.analyze;

import com.zoosool.enums.TickAction;
import com.zoosool.model.TickEvent;

import java.util.function.Consumer;

public interface TickStatsCalculator {
    void onEvent(TickEvent event);

    default void onStart() { }

    default void onStop() { }

    static TickStatsCalculator noop(Consumer<String> log, String symbol) {
        return event -> {
            if (event.action() != TickAction.TICK) {
                log.accept("[STATS] symbol=" + symbol + " action=" + event.action());
            }
        };
    }
}
