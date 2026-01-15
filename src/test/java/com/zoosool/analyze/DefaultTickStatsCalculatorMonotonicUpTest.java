package com.zoosool.analyze;

import com.zoosool.enums.TickDecision;
import com.zoosool.enums.TickStatsState;
import com.zoosool.model.TickEvent;
import com.zoosool.model.TickStatsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTickStatsCalculatorMonotonicUpTest {

    @Test
    void monotonicIncreasingSequence_shouldReachOkState_andHaveLowCrossings_andTradeDecision() {
        // Collect snapshots emitted by calculator (every LOG_EVERY_SECONDS ticks)
        SnapshotCollectorSink sink = new SnapshotCollectorSink();

        DefaultTickStatsCalculator calc = new DefaultTickStatsCalculator("stpRNG4", sink);

        // Feed strictly increasing quotes: 1..120
        for (int i = 1; i <= DefaultTickStatsCalculator.LONG_WINDOW; i++) {
            calc.onEvent(TickEvent.tick("stpRNG4", (double) i));
        }

        assertFalse(sink.snapshots.isEmpty(), "Snapshots must be emitted");

        TickStatsSnapshot last = sink.last();
        assertNotNull(last, "Last snapshot must exist");

        // After LONG_WINDOW ticks we expect stable metrics
        assertEquals(TickStatsState.OK, last.state(), "State must be OK after filling long window");
        assertEquals(TickDecision.TRADE, last.decision(), "Decision should be TRADE for monotonic trend");

        // No zero-delta anomalies
        assertEquals(0, last.zeroShort(), "zeroShort must be 0 for strictly increasing sequence");

        // Crossings should be near zero in monotonic trend
        assertNotNull(last.xmaShort(), "xmaShort must be present in OK state");
        assertNotNull(last.xmaLong(), "xmaLong must be present in OK state");
        assertTrue(last.xmaShort() <= 1, "xmaShort should be near 0 for monotonic trend");
        assertTrue(last.xmaLong() <= 1, "xmaLong should be near 0 for monotonic trend");

        // ADL should be high (long runs). For monotonic: one long run -> large ADL.
        assertNotNull(last.adlShort(), "adlShort must be present in OK state");
        assertNotNull(last.adlLong(), "adlLong must be present in OK state");
        assertTrue(last.adlShort() >= 10.0, "adlShort should be large for monotonic trend");
        assertTrue(last.adlLong() >= 50.0, "adlLong should be very large for monotonic trend");

        // Sanity: config echoes
        assertEquals(DefaultTickStatsCalculator.LONG_WINDOW, last.longWindow());
        assertEquals(DefaultTickStatsCalculator.SHORT_WINDOW, last.shortWindow());
        assertEquals(DefaultTickStatsCalculator.MA_WINDOW, last.maWindow());

        // Buffers must be full
        assertEquals(DefaultTickStatsCalculator.LONG_WINDOW, last.bufLong());
        assertEquals(DefaultTickStatsCalculator.SHORT_WINDOW, last.bufShort());
    }

    /**
     * Simple sink collecting all snapshots in memory.
     */
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
