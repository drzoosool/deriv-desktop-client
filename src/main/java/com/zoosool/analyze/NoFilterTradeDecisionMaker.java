package com.zoosool.analyze;

import com.zoosool.deriv.DerivTradingService;
import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.Contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class NoFilterTradeDecisionMaker implements TradeDecisionMaker {

    private static final BigDecimal[] LADDER = {
            BigDecimal.valueOf(1),
            BigDecimal.valueOf(5),
            BigDecimal.valueOf(23),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(465)
    };

    private static final int TRADE_SEC_A = 29;
    private static final int TRADE_SEC_B = 59;

    /**
     * Ensures only ONE trade per epochSecond across all instances/threads.
     */
    private static final AtomicLong GLOBAL_LAST_TRADE_EPOCH_SECOND = new AtomicLong(-1);

    /**
     * Global in-flight guard.
     * We intentionally allow only ONE in-flight trade in the whole process.
     */
    private static final String GLOBAL_IN_FLIGHT_KEY = "GLOBAL";

    private static final ConcurrentMap<String, InFlightTrade> GLOBAL_IN_FLIGHT = new ConcurrentHashMap<>();

    private static boolean tryAcquireGlobalSecond(long epochSecond) {
        while (true) {
            long prev = GLOBAL_LAST_TRADE_EPOCH_SECOND.get();
            if (prev == epochSecond) {
                return false;
            }
            if (GLOBAL_LAST_TRADE_EPOCH_SECOND.compareAndSet(prev, epochSecond)) {
                return true;
            }
        }
    }

    private final DerivTradingService trading;
    private final Consumer<String> log;
    private final ZoneId zone = ZoneId.systemDefault();

    // Progression (instance state)
    private int ladderIdx = 0;

    public NoFilterTradeDecisionMaker(DerivTradingService trading, Consumer<String> logger) {
        this.trading = Objects.requireNonNull(trading, "trading");
        this.log = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void decideAndTrade(String symbol, AnalyzeContainer analyze) {
        if (!isAllowedSymbol(symbol)) {
            return;
        }

        Instant now = Instant.now();
        LocalDateTime ldt = LocalDateTime.ofInstant(now, zone);

        if (!isTradeSecond(ldt)) {
            return;
        }

        long epochSecond = now.getEpochSecond();

        // 1) Anti-dup per second
        if (!tryAcquireGlobalSecond(epochSecond)) {
            return;
        }

        // 2) Build contract with current ladder stake
        StakeSnapshot stake = snapshotStake();
        Contract contract = new Contract(symbol, stake.stakePerSide(), 10, "t", "stake");

        // 3) Try to reserve global in-flight slot atomically
        if (!tryReserveInFlight(epochSecond, symbol, stake.stakePerSide())) {
            log.accept("⏳ SKIP (in-flight) makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " time=" + ldt
                    + " epochSecond=" + epochSecond);
            return;
        }

        // 4) Start async trade + completion handler
        startTradeAndTrackResult(epochSecond, symbol, contract, stake.stakePerSide());

        log.accept("🟦 TRADE makerId=" + System.identityHashCode(this)
                + " thread=" + Thread.currentThread().getName()
                + " time=" + ldt
                + " epochSecond=" + epochSecond
                + " symbol=" + symbol
                + " stakePerSide=" + stake.stakePerSide()
                + " total=" + stake.stakePerSide().multiply(BigDecimal.valueOf(2))
                + " ladderIdx=" + stake.ladderIdxAtSend());
    }

    private boolean isAllowedSymbol(String symbol) {
        return symbol != null && symbol.equals("stpRNG");
    }

    private boolean isTradeSecond(LocalDateTime ldt) {
        int sec = ldt.getSecond();
        return sec == TRADE_SEC_A || sec == TRADE_SEC_B;
    }

    private StakeSnapshot snapshotStake() {
        synchronized (this) {
            BigDecimal stakePerSide = LADDER[ladderIdx];
            return new StakeSnapshot(ladderIdx, stakePerSide);
        }
    }

    /**
     * Atomically reserve the single global in-flight slot.
     */
    private boolean tryReserveInFlight(long epochSecond, String symbol, BigDecimal stakePerSide) {
        InFlightTrade placeholder = new InFlightTrade(epochSecond, symbol, stakePerSide, null);

        InFlightTrade prev = GLOBAL_IN_FLIGHT.putIfAbsent(GLOBAL_IN_FLIGHT_KEY, placeholder);
        return prev == null;
    }

    private void startTradeAndTrackResult(
            long epochSecond,
            String symbol,
            Contract contract,
            BigDecimal stakePerSide
    ) {
        CompletableFuture<DerivTradingService.BothBuyStatus> fut =
                trading.buyBothAndWaitAsync(contract);

        // Replace placeholder with the real future (still the same global slot).
        GLOBAL_IN_FLIGHT.put(GLOBAL_IN_FLIGHT_KEY, new InFlightTrade(epochSecond, symbol, stakePerSide, fut));

        fut.whenComplete((status, ex) -> {
            try {
                applyResult(symbol, status, ex);
            } finally {
                // Release only if the stored value matches (safe against accidental overwrite).
                GLOBAL_IN_FLIGHT.remove(GLOBAL_IN_FLIGHT_KEY, new InFlightTrade(epochSecond, symbol, stakePerSide, fut));
            }
        });
    }

    private void applyResult(String symbol, DerivTradingService.BothBuyStatus status, Throwable ex) {
        boolean success;

        if (ex != null) {
            int prevIdx;
            int newIdx;
            synchronized (this) {
                prevIdx = ladderIdx;
                ladderIdx = Math.min(ladderIdx + 1, LADDER.length - 1);
                newIdx = ladderIdx;
            }

            log.accept("💥 RESULT makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " status=ERROR->FAIL"
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + LADDER[newIdx]
                    + " err=" + ex);
            return;
        }

        success = status != null && status.isSuccess();

        int prevIdx;
        int newIdx;
        synchronized (this) {
            prevIdx = ladderIdx;
            if (success) {
                ladderIdx = 0;
            } else {
                ladderIdx = Math.min(ladderIdx + 1, LADDER.length - 1);
            }
            newIdx = ladderIdx;
        }

        log.accept((success ? "✅" : "❌") + " RESULT makerId=" + System.identityHashCode(this)
                + " symbol=" + symbol
                + " status=" + (success ? "SUCCESS" : "FAIL")
                + " ladder " + prevIdx + "->" + newIdx
                + " nextStake=" + LADDER[newIdx]);
    }

    private record StakeSnapshot(int ladderIdxAtSend, BigDecimal stakePerSide) {}

    private record InFlightTrade(
            long epochSecond,
            String symbol,
            BigDecimal stakePerSide,
            CompletableFuture<DerivTradingService.BothBuyStatus> future
    ) {}
}
