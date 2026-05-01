// StreakFourFixedDurationTradeDecisionMaker.java
package com.zoosool.analyze;

import com.zoosool.deriv.BalanceHolder;
import com.zoosool.deriv.DerivTradingService;
import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.Contract;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Rule-based auto-trader for stpRNG.
 *
 * Signal:
 * - 4 consecutive ticks in the same direction (UP or DOWN) → trade.
 * - Contract duration: fixed 4 seconds.
 *
 * Ladder:
 * - Configured via LADDER constant; reset on SUCCESS, advance on FAIL.
 * - Stop trading when FAIL on last ladder step.
 *
 * In-flight guard:
 * - While a trade is active (inFlight != null) no new trade is opened.
 * - As soon as result arrives → ready for next signal immediately.
 *
 * No cooldown. No active window. No per-minute limit.
 *
 * Dataset logging:
 * - Tape: sliding 600-second window (1 Hz).
 * - Warmup: 600 seconds before first trade.
 * - On trade CLOSE: one JSONL record with last 600 sec ending at result time.
 */
public final class StreakFourFixedDurationTradeDecisionMaker implements TradeDecisionMaker {

    private static final String ALLOWED_SYMBOL = "stpRNG3";

    // TODO: tune before enabling live trading
//    private static final BigDecimal[] LADDER = {
//            BigDecimal.valueOf(2),
//            BigDecimal.valueOf(5),
//            BigDecimal.valueOf(15),
//            BigDecimal.valueOf(45),
//            BigDecimal.valueOf(135),
//            BigDecimal.valueOf(405),
//            BigDecimal.valueOf(1300),
//    };

    private static final BigDecimal[] LADDER = {
            BigDecimal.valueOf(1),
            BigDecimal.valueOf(4),
            BigDecimal.valueOf(14),
            BigDecimal.valueOf(50),
            BigDecimal.valueOf(172),
            BigDecimal.valueOf(600)
    };

    private static final int DIR_STREAK_REQUIRED = 4;
    private static final int CONTRACT_DURATION_SECONDS = 6;

    private static final int TAPE_KEEP_SECONDS = 600;
    private static final int MIN_HISTORY_SECONDS_BEFORE_TRADING = TAPE_KEEP_SECONDS;

    private static final String DEFAULT_DURATION_UNIT = "t";
    private static final String DEFAULT_STAKE_TYPE = "stake";
    //private static final String DEFAULT_STAKE_TYPE = "payout";

    private enum Direction { UP, DOWN, NONE }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final DerivTradingService trading;
    @SuppressWarnings("unused")
    private final BalanceHolder balanceHolder;
    private final Consumer<String> log;
    private final ZoneId zone = ZoneId.systemDefault();

    // -------------------------------------------------------------------------
    // Tape
    // -------------------------------------------------------------------------

    private final GlobalLevelTape tape = new GlobalLevelTape(TAPE_KEEP_SECONDS);
    private final TradeHistoryRecorder recorder;

    private long firstTapeEpochSecond = -1;
    private boolean warmupStartLogged = false;
    private boolean warmupEndLogged = false;

    private long lastProcessedEpochSecond = -1;
    private Long lastProcessedLevel = null;

    // -------------------------------------------------------------------------
    // Signal state
    // -------------------------------------------------------------------------

    private Direction direction = Direction.NONE;
    private int directionStreak = 0;
    private boolean armed = false;

    // -------------------------------------------------------------------------
    // Trading state
    // -------------------------------------------------------------------------

    private int ladderIdx = 0;

    private long nextTradeSeq = 1;
    private long lastSettledTradeSeq = 0;

    private InFlightTrade inFlight = null;

    private boolean stopped = false;
    private String stopReason = null;
    private LocalDateTime stopAt = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public StreakFourFixedDurationTradeDecisionMaker(
            DerivTradingService trading,
            BalanceHolder balanceHolder,
            Consumer<String> logger
    ) {
        this.trading = Objects.requireNonNull(trading, "trading");
        this.balanceHolder = Objects.requireNonNull(balanceHolder, "balanceHolder");
        this.log = Objects.requireNonNull(logger, "logger");
        this.recorder = new TradeHistoryRecorder(Path.of("trade-data"), s -> {});
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    @Override
    public void decideAndTrade(String symbol, AnalyzeContainer analyze) {
        // TRADING IS DISABLED — remove this block to enable
//        if (true) {
//            return;
//        }

        if (!ALLOWED_SYMBOL.equals(symbol)) {
            return;
        }

        Long level = extractLastLevel(analyze);
        if (level == null) {
            return;
        }

        Instant now = Instant.now();
        long nowEpochSecond = now.getEpochSecond();
        LocalDateTime ldt = LocalDateTime.ofInstant(now, zone);

        TradePlan plan = null;

        synchronized (this) {
            if (firstTapeEpochSecond < 0) {
                firstTapeEpochSecond = nowEpochSecond;
            }

            tape.put(nowEpochSecond, level);

            // Same second: skip (1 Hz)
            if (nowEpochSecond == lastProcessedEpochSecond) {
                return;
            }

            // Gap in ticks → reset streak to avoid phantom signals
            if (lastProcessedEpochSecond >= 0 && (nowEpochSecond - lastProcessedEpochSecond) > 1) {
                resetStreakLocked();
                lastProcessedLevel = null;
            }

            updateDirectionAndStreakLocked(level);

            if (directionStreak >= DIR_STREAK_REQUIRED) {
                armed = true;
            }

            boolean warmedUp = isWarmedUpLocked(nowEpochSecond);
            maybeLogWarmupStateLocked(ldt, nowEpochSecond, warmedUp);

            if (!stopped && armed && inFlight == null) {

                StakeSnapshot stake = snapshotStakeLocked();

                Contract contract = new Contract(
                        symbol,
                        stake.stakePerSide(),
                        CONTRACT_DURATION_SECONDS,
                        DEFAULT_DURATION_UNIT,
                        DEFAULT_STAKE_TYPE
                );

                long tradeSeq = nextTradeSeq++;

                inFlight = new InFlightTrade(tradeSeq, nowEpochSecond, symbol, stake.stakePerSide(), null);
                armed = false;

                plan = new TradePlan(
                        tradeSeq,
                        nowEpochSecond,
                        ldt,
                        symbol,
                        level,
                        contract,
                        stake,
                        direction
                );
            }

            lastProcessedEpochSecond = nowEpochSecond;
            lastProcessedLevel = level;
        }

        if (plan != null) {
            log.accept("🟦 RULE_TRADE"
                    + " time=" + plan.ldt()
                    + " tradeSeq=" + plan.tradeSeq()
                    + " epochSecond=" + plan.epochSecond()
                    + " symbol=" + plan.symbol()
                    + " level=" + plan.level()
                    + " dir=" + plan.signalDirection()
                    + " streak=" + DIR_STREAK_REQUIRED
                    + " stakePerSide=" + plan.stake().stakePerSide()
                    + " ladderIdx=" + plan.stake().ladderIdxAtSend()
                    + " durationSec=" + CONTRACT_DURATION_SECONDS);

            CompletableFuture<DerivTradingService.BuySellResult> fut = trading.buySellAndAwait(plan.contract());
            wireInFlightFuture(plan, fut);
        }
    }

    // -------------------------------------------------------------------------
    // Async wiring
    // -------------------------------------------------------------------------

    private void wireInFlightFuture(TradePlan plan, CompletableFuture<DerivTradingService.BuySellResult> fut) {
        synchronized (this) {
            InFlightTrade cur = inFlight;
            if (cur != null && cur.tradeSeq() == plan.tradeSeq()) {
                inFlight = new InFlightTrade(cur.tradeSeq(), cur.epochSecond(), cur.symbol(), cur.stakePerSide(), fut);
            }
        }

        fut.whenComplete((res, ex) -> {
            try {
                applyResult(plan, res, ex);
            } finally {
                synchronized (this) {
                    InFlightTrade cur = inFlight;
                    if (cur != null && cur.tradeSeq() == plan.tradeSeq()) {
                        inFlight = null;
                    }
                }
            }
        });
    }

    private void applyResult(TradePlan plan, DerivTradingService.BuySellResult res, Throwable ex) {
        long resultEpoch = Instant.now().getEpochSecond();
        LocalDateTime ldt = LocalDateTime.now(zone);

        Throwable rootEx = (ex == null) ? null : unwrapCompletion(ex);
        String exText = (rootEx == null) ? "" : (" ex=" + rootEx);

        boolean success = (rootEx == null && res == DerivTradingService.BuySellResult.SUCCESS);

        int prevIdx;
        int newIdx;
        boolean failOnLastStep;
        String timelineRle;

        synchronized (this) {
            if (plan.tradeSeq() <= lastSettledTradeSeq) {
                log.accept("🟧 RESULT_IGNORED_OUTDATED"
                        + " time=" + ldt
                        + " tradeSeq=" + plan.tradeSeq()
                        + " lastSettled=" + lastSettledTradeSeq
                        + " symbol=" + plan.symbol()
                        + " res=" + (res == null ? "null" : res)
                        + exText);
                return;
            }
            lastSettledTradeSeq = plan.tradeSeq();

            if (stopped) {
                log.accept("🟥 RESULT_IGNORED_STOPPED"
                        + " time=" + ldt
                        + " tradeSeq=" + plan.tradeSeq()
                        + " symbol=" + plan.symbol()
                        + " res=" + (res == null ? "null" : res)
                        + exText
                        + " stoppedAt=" + stopAt
                        + " reason=" + stopReason);
                return;
            }

            prevIdx = ladderIdx;
            failOnLastStep = (!success) && (prevIdx == LADDER.length - 1);

            if (success) {
                ladderIdx = 0;
            } else {
                ladderIdx = Math.min(ladderIdx + 1, LADDER.length - 1);
            }

            newIdx = ladderIdx;

            if (failOnLastStep) {
                stopped = true;
                stopReason = (rootEx != null ? "LAST_STEP_ERROR->FAIL" : "LAST_STEP_FAIL");
                stopAt = ldt;
            }

            timelineRle = tape.snapshotLastRleJsonWithTimestamps(resultEpoch);
        }

        recorder.recordTradeClosed(
                resultEpoch,
                plan.symbol(),
                plan.tradeSeq(),
                plan.epochSecond(),
                plan.durationSeconds(),
                DEFAULT_STAKE_TYPE,
                plan.stake().stakePerSide().toPlainString(),
                plan.stake().ladderIdxAtSend(),
                prevIdx,
                newIdx,
                LADDER[newIdx].toPlainString(),
                success ? "SUCCESS" : "FAIL",
                (rootEx == null ? null : rootEx.toString()),
                -1L, // no cooldown
                resultEpoch,
                TAPE_KEEP_SECONDS,
                timelineRle
        );

        if (failOnLastStep) {
            log.accept("🟥 STOP_TRADING"
                    + " time=" + ldt
                    + " tradeSeq=" + plan.tradeSeq()
                    + " symbol=" + plan.symbol()
                    + " res=" + (res == null ? "null" : res.name())
                    + exText
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + LADDER[newIdx]);
            return;
        }

        if (success) {
            log.accept("✅ RESULT"
                    + " time=" + ldt
                    + " tradeSeq=" + plan.tradeSeq()
                    + " symbol=" + plan.symbol()
                    + " res=SUCCESS"
                    + exText
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + LADDER[newIdx]);
        } else {
            log.accept("❌ RESULT"
                    + " time=" + ldt
                    + " tradeSeq=" + plan.tradeSeq()
                    + " symbol=" + plan.symbol()
                    + " res=FAIL"
                    + exText
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + LADDER[newIdx]);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Throwable unwrapCompletion(Throwable ex) {
        Throwable t = ex;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private void resetStreakLocked() {
        direction = Direction.NONE;
        directionStreak = 0;
        armed = false;
    }

    private boolean isWarmedUpLocked(long nowEpochSecond) {
        if (firstTapeEpochSecond < 0) return false;
        return (nowEpochSecond - firstTapeEpochSecond) >= (MIN_HISTORY_SECONDS_BEFORE_TRADING - 1L);
    }

    private void maybeLogWarmupStateLocked(LocalDateTime ldt, long nowEpochSecond, boolean warmedUp) {
        if (!warmupStartLogged) {
            warmupStartLogged = true;
            log.accept("🟨 WARMUP_START time=" + ldt + " needSec=" + MIN_HISTORY_SECONDS_BEFORE_TRADING);
        }
        if (warmedUp && !warmupEndLogged) {
            warmupEndLogged = true;
            long age = nowEpochSecond - firstTapeEpochSecond;
            log.accept("🟩 WARMUP_END time=" + ldt + " historySec=" + age + " tapeHorizonSec=" + TAPE_KEEP_SECONDS);
        }
    }

    private void updateDirectionAndStreakLocked(Long currentLevel) {
        if (lastProcessedLevel == null) {
            resetStreakLocked();
            return;
        }

        long diff = currentLevel - lastProcessedLevel;

        if (diff == 0) {
            resetStreakLocked();
            return;
        }

        Direction newDir = diff > 0 ? Direction.UP : Direction.DOWN;

        if (newDir == direction) {
            directionStreak++;
        } else {
            direction = newDir;
            directionStreak = 1;
            armed = false;
        }
    }

    private StakeSnapshot snapshotStakeLocked() {
        return new StakeSnapshot(ladderIdx, LADDER[ladderIdx]);
    }

    private static Long extractLastLevel(AnalyzeContainer analyze) {
        if (analyze == null) return null;
        AnalyzeContainer.LevelsEdgesSnapshot snap = analyze.snapshotLevelsEdges();
        if (snap == null || snap.size() <= 0) return null;
        return snap.last();
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    private record StakeSnapshot(int ladderIdxAtSend, BigDecimal stakePerSide) {}

    private record InFlightTrade(
            long tradeSeq,
            long epochSecond,
            String symbol,
            BigDecimal stakePerSide,
            CompletableFuture<DerivTradingService.BuySellResult> future
    ) {}

    private record TradePlan(
            long tradeSeq,
            long epochSecond,
            LocalDateTime ldt,
            String symbol,
            long level,
            Contract contract,
            StakeSnapshot stake,
            Direction signalDirection
    ) {
        int durationSeconds() {
            return CONTRACT_DURATION_SECONDS;
        }
    }
}