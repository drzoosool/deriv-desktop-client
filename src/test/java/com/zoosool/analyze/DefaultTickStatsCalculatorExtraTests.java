package com.zoosool.analyze;

import com.zoosool.enums.TickDecision;
import com.zoosool.enums.TickStatsState;
import com.zoosool.model.TickEvent;
import com.zoosool.model.TickStatsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTickStatsCalculatorExtraTests {

    @Test
    void reset_shouldClearWindows_andReturnToWarmup() {
        SnapshotCollectorSink sink = new SnapshotCollectorSink();
        DefaultTickStatsCalculator calc = new DefaultTickStatsCalculator("stpRNG4", sink);

        // Fill long window => OK
        for (int i = 1; i <= DefaultTickStatsCalculator.LONG_WINDOW; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }

        TickStatsSnapshot beforeReset = sink.last();
        assertNotNull(beforeReset);
        assertEquals(TickStatsState.OK, beforeReset.state());
        assertEquals(DefaultTickStatsCalculator.LONG_WINDOW, beforeReset.bufLong());

        // Reset
        calc.onEvent(TickEvent.reset("stpRNG4"));

        // Add a few ticks after reset
        for (int i = 1; i <= 10; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", 1000.0 + i));
        }

        TickStatsSnapshot afterReset = sink.last();
        assertNotNull(afterReset);

        // After reset + only 10 ticks: short window not filled => WARMUP_S
        assertEquals(TickStatsState.WARMUP_S, afterReset.state());
        assertEquals(TickDecision.NA, afterReset.decision());

        assertEquals(10, afterReset.bufLong());
        assertEquals(10, afterReset.bufShort());

        // Metrics must be NA until windows are filled
        assertNull(afterReset.adlLong());
        assertNull(afterReset.adlShort());
        assertNull(afterReset.xmaLong());
        assertNull(afterReset.xmaShort());

        assertEquals(0, afterReset.zeroShort());
    }

    @Test
    void twoZeroDeltasWithinShortWindow_shouldBan() {
        SnapshotCollectorSink sink = new SnapshotCollectorSink();
        DefaultTickStatsCalculator calc = new DefaultTickStatsCalculator("stpRNG4", sink);

        // Fill exactly short window (30) with increasing values (no zeros)
        for (int i = 1; i <= DefaultTickStatsCalculator.SHORT_WINDOW; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }

        // Now introduce two zero-deltas inside last 30 ticks:
        // ... 30, 30  => one zero delta
        calc.onEvent(TickEvent.tick("stpRNG4", 30.0));
        // ... 31, 31  => second zero delta
        calc.onEvent(TickEvent.tick("stpRNG4", 31.0));
        calc.onEvent(TickEvent.tick("stpRNG4", 31.0));

        // Ensure we get an emission after these ticks (calculator emits every 2 ticks).
        // We already sent 3 ticks; send one more to be safe.
        calc.onEvent(TickEvent.tick("stpRNG4", 32.0));

        TickStatsSnapshot last = sink.last();
        assertNotNull(last);

        assertEquals(TickStatsState.BANNED, last.state(), "Must be BANNED when zeroShort >= threshold");
        assertEquals(TickDecision.NA, last.decision(), "Decision must be NA when BANNED");
        assertNotNull(last.reason(), "Ban reason must be present");

        assertTrue(last.zeroShort() >= DefaultTickStatsCalculator.ZERO_DELTA_BAN_THRESHOLD,
                "zeroShort must reach ban threshold");
    }

    @Test
    void tradeThenSawInShortWindow_shouldDegradeDecision() {
        SnapshotCollectorSink sink = new SnapshotCollectorSink();
        DefaultTickStatsCalculator calc = new DefaultTickStatsCalculator("stpRNG4", sink);

        // 120 ticks monotonic up => OK and (with current thresholds) TRADE
        for (int i = 1; i <= DefaultTickStatsCalculator.LONG_WINDOW; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }

        TickStatsSnapshot before = sink.last();
        assertNotNull(before);
        assertEquals(TickStatsState.OK, before.state());

        // Now last 30 ticks become a saw (1,2,1,2...) to degrade short metrics
        // Keep feeding within same symbol; long window will now include mixed,
        // but short window should be dominated by saw.
        for (int i = 0; i < DefaultTickStatsCalculator.SHORT_WINDOW; i++) {
            double q = (i % 2 == 0) ? 1.0 : 2.0;
            calc.onEvent(TickEvent.tick("stpRNG4", q));
        }

        // Ensure snapshot emission after feeding 30 ticks (emit every 2 ticks)
        TickStatsSnapshot after = sink.last();
        assertNotNull(after);
        assertEquals(TickStatsState.OK, after.state());

        // In degraded short regime, decision should not remain TRADE.
        assertNotEquals(TickDecision.TRADE, after.decision(),
                "Decision should degrade when short window becomes saw-like");
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
