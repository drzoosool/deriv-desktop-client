package com.zoosool.analyze;

import com.zoosool.enums.TickStatsState;
import com.zoosool.model.TickEvent;
import com.zoosool.model.TickStatsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTickStatsCalculatorUpThenDownTest {

    @Test
    void sixtyUp_thenSixtyDown_shouldBeOk_andHaveAboutOneMaCrossing() {
        SnapshotCollectorSink sink = new SnapshotCollectorSink();
        DefaultTickStatsCalculator calc = new DefaultTickStatsCalculator("stpRNG4", sink);

        // 60 ticks up: 1..60
        for (int i = 1; i <= 60; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }

        // 60 ticks down: 59..0 (continue strictly down)
        for (int i = 59; i >= 0; i--) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }

        assertFalse(sink.snapshots.isEmpty(), "Snapshots must be emitted");
        TickStatsSnapshot last = sink.last();
        assertNotNull(last);

        assertEquals(TickStatsState.OK, last.state(), "State must be OK after 120 ticks");
        assertEquals(0, last.zeroShort(), "No zero deltas expected here");

        assertNotNull(last.xmaShort(), "xmaShort must be present in OK state");
        assertNotNull(last.xmaLong(), "xmaLong must be present in OK state");

        // We expect a single regime flip -> small number of MA crossings, not a lot.
        // In ideal math it's often ~1. In practice it can be 1-2 due to discrete MA/touch.
        assertTrue(last.xmaLong() >= 1, "There should be at least one MA crossing in long window");
        assertTrue(last.xmaLong() <= 3, "There should not be many MA crossings for one flip (expected ~1)");

        // Short window is the last 30 ticks (mostly down), should also show low crossings
        assertTrue(last.xmaShort() <= 2, "Short window should have low MA crossings in clean down leg");

        // ADL should still be high-ish (runs are long in both directions)
        assertNotNull(last.adlLong());
        assertNotNull(last.adlShort());
        assertTrue(last.adlShort() >= 10.0, "Short ADL should be large (mostly monotonic down)");
        assertTrue(last.adlLong() >= 20.0, "Long ADL should be large (two long runs)");

        // Buffers full
        assertEquals(DefaultTickStatsCalculator.LONG_WINDOW, last.bufLong());
        assertEquals(DefaultTickStatsCalculator.SHORT_WINDOW, last.bufShort());
    }

    private static final class SnapshotCollectorSink implements TickStatsSink {
        private final List<TickStatsSnapshot> snapshots = new ArrayList<>();

        @Override
        public void onSnapshot(TickStatsSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        TickStatsSnapshot last() {
            return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
        }
    }
}
