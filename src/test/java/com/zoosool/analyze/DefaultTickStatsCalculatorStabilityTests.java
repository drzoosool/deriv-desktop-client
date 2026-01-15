package com.zoosool.analyze;

import com.zoosool.enums.TickStatsState;
import com.zoosool.model.TickEvent;
import com.zoosool.model.TickStatsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTickStatsCalculatorStabilityTests {

    @Test
    void longMonotonicRun_shouldNeverBan_andShouldKeepCrossingsLow() {
        SnapshotCollectorSink sink = new SnapshotCollectorSink();
        DefaultTickStatsCalculator calc = new DefaultTickStatsCalculator("stpRNG4", sink);

        // Feed more than LONG_WINDOW to force ring-buffer wrap (regression guard).
        int totalTicks = DefaultTickStatsCalculator.LONG_WINDOW + 80; // 200 for default 120
        for (int i = 1; i <= totalTicks; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }

        assertFalse(sink.snapshots.isEmpty(), "Snapshots must be emitted");

        // 1) Never banned and zeroShort always 0 for strictly increasing sequence
        for (TickStatsSnapshot s : sink.snapshots) {
            assertNotEquals(TickStatsState.BANNED, s.state(), "Must never be BANNED on monotonic run");
            assertEquals(0, s.zeroShort(), "zeroShort must be 0 on monotonic run");
        }

        // 2) Once state is OK, crossings should remain near zero
        boolean seenOk = false;
        for (TickStatsSnapshot s : sink.snapshots) {
            if (s.state() == TickStatsState.OK) {
                seenOk = true;

                assertNotNull(s.xmaShort(), "xmaShort must exist in OK");
                assertNotNull(s.xmaLong(), "xmaLong must exist in OK");

                assertTrue(s.xmaShort() <= 1, "xmaShort should stay near 0 on monotonic run");
                assertTrue(s.xmaLong() <= 1, "xmaLong should stay near 0 on monotonic run");
            }
        }
        assertTrue(seenOk, "We must eventually reach OK state");
    }

    @Test
    void stopEvent_shouldNotEmitSnapshot_andShouldNotBreakFurtherProcessing() {
        SnapshotCollectorSink sink = new SnapshotCollectorSink();
        DefaultTickStatsCalculator calc = new DefaultTickStatsCalculator("stpRNG4", sink);

        for (int i = 1; i <= 10; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }
        assertFalse(sink.snapshots.isEmpty(), "Expected some snapshots during warmup");
        int beforeStopCount = sink.snapshots.size();

        assertDoesNotThrow(() -> calc.onEvent(TickEvent.stop("stpRNG4")));
        assertEquals(beforeStopCount, sink.snapshots.size(), "STOP must not emit a snapshot");

        for (int i = 11; i <= 30; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }

        assertTrue(sink.snapshots.size() > beforeStopCount, "Calculator must keep emitting snapshots after STOP if ticks continue");
    }

    private static final class SnapshotCollectorSink implements TickStatsSink {
        private final List<TickStatsSnapshot> snapshots = new ArrayList<>();

        @Override
        public void onSnapshot(TickStatsSnapshot snapshot) {
            snapshots.add(snapshot);
        }
    }
}
