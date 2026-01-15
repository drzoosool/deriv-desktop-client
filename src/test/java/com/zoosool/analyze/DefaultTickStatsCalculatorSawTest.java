package com.zoosool.analyze;

import com.zoosool.enums.TickDecision;
import com.zoosool.enums.TickStatsState;
import com.zoosool.model.TickEvent;
import com.zoosool.model.TickStatsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTickStatsCalculatorSawTest {

    @Test
    void saw_1_2_repeating_shouldHaveLowAdl_andHighMaCrossings_andNoTrade() {
        SnapshotCollectorSink sink = new SnapshotCollectorSink();
        DefaultTickStatsCalculator calc = new DefaultTickStatsCalculator("stpRNG4", sink);

        // 120 ticks total: 1,2,1,2,...
        for (int i = 0; i < DefaultTickStatsCalculator.LONG_WINDOW; i++) {
            double quote = (i % 2 == 0) ? 1.0 : 2.0;
            calc.onEvent(TickEvent.tick("stpRNG4", quote));
        }

        assertFalse(sink.snapshots.isEmpty(), "Snapshots must be emitted");
        TickStatsSnapshot last = sink.last();
        assertNotNull(last);

        assertEquals(TickStatsState.OK, last.state(), "State must be OK after 120 ticks");

        // In saw mode ADL should be ~1 (direction changes every tick).
        assertNotNull(last.adlShort(), "adlShort must be present in OK state");
        assertNotNull(last.adlLong(), "adlLong must be present in OK state");
        assertTrue(last.adlShort() <= 1.2, "adlShort should be close to 1 for saw");
        assertTrue(last.adlLong() <= 1.2, "adlLong should be close to 1 for saw");

        // MA crossings should be high (frequent flips around MA)
        assertNotNull(last.xmaShort(), "xmaShort must be present in OK state");
        assertNotNull(last.xmaLong(), "xmaLong must be present in OK state");

        // Exact values depend on MA behavior; we check "high enough".
        assertTrue(last.xmaShort() >= 8, "xmaShort should be high for saw");
        assertTrue(last.xmaLong() >= 20, "xmaLong should be high for saw");

        // No zero deltas
        assertEquals(0, last.zeroShort(), "No zero deltas expected in 1/2 saw");

        // With current classifier, saw should be NO_TRADE
        assertEquals(TickDecision.NO_TRADE, last.decision(), "Saw mode must be NO_TRADE");
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
