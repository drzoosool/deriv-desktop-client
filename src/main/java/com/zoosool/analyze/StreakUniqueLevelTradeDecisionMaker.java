// StreakUniqueLevelTradeDecisionMaker.java
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Rule-based auto-trader for stpRNG.
 *
 * Trading:
 * - Stake ladder per side (BUY-SELL, two sides).
 * - SUCCESS if DerivTradingService.BuySellResult == SUCCESS
 * - Ladder resets on SUCCESS; increases on FAIL.
 *
 * Cooldown:
 * - After any FAIL wait COOLDOWN_AFTER_FAIL_SECONDS (ignore signals).
 *
 * Important fix:
 * - NO "stale inFlight release" (overlapping trades / out-of-order ladder resets).
 * - Cooldown starts from RESULT time (not from plan epochSecond).
 *
 * Dataset logging (agreement):
 * - Tape is fixed 600 seconds (1Hz ring).
 * - First warmup is 600 seconds (we want full dataset window).
 * - On trade CLOSE: write ONE dataset record (JSONL) with last 600 seconds ending at result time.
 * - Tape is NOT cleared (sliding window); trading logic continues as before.
 */
public final class StreakUniqueLevelTradeDecisionMaker implements TradeDecisionMaker {

    private static final String ALLOWED_SYMBOL = "stpRNG4";

    private static final BigDecimal[] LADDER = {
            BigDecimal.valueOf(1),
            BigDecimal.valueOf(11),
            BigDecimal.valueOf(112)
    };

    // Dataset window and tape horizon.
    private static final int TAPE_KEEP_SECONDS = 600;

    // First warmup must be full 600 seconds.
    private static final int MIN_HISTORY_SECONDS_BEFORE_TRADING = TAPE_KEEP_SECONDS;

    private static final int LEVEL_REPEAT_MAX_AGE_SECONDS = 30;
    private static final int DIR_STREAK_MIN_SECONDS = 3;

    private static final int ACTIVE_WINDOW_SEC_FROM = 3;
    private static final int ACTIVE_WINDOW_SEC_TO = 35;

    private static final long COOLDOWN_AFTER_FAIL_SECONDS = 120;

    private static final String DEFAULT_DURATION_UNIT = "s";
    private static final String DEFAULT_STAKE_TYPE = "stake";

    private enum Direction { UP, DOWN, NONE }

    private final DerivTradingService trading;
    @SuppressWarnings("unused")
    private final BalanceHolder balanceHolder;
    private final Consumer<String> log;
    private final ZoneId zone = ZoneId.systemDefault();

    // Tape & level-seen map
    private final GlobalLevelTape tape = new GlobalLevelTape(TAPE_KEEP_SECONDS);
    private final LongLongTtlMap lastSeenEpochByLevel = new LongLongTtlMap(TAPE_KEEP_SECONDS);

    // Dataset recorder: one file per app run
    private final TradeHistoryRecorder recorder;

    private long firstTapeEpochSecond = -1;
    private boolean warmupStartLogged = false;
    private boolean warmupEndLogged = false;

    private long lastProcessedEpochSecond = -1;
    private Long lastProcessedLevel = null;

    private Direction direction = Direction.NONE;
    private int directionStreak = 0;
    private boolean armed = false;

    private long lastTradeMinuteBucket = -1;

    private int ladderIdx = 0;
    private long cooldownUntilEpochSecond = -1;

    /**
     * Protect against out-of-order completion or accidental overlapping futures.
     */
    private long nextTradeSeq = 1;
    private long lastSettledTradeSeq = 0;

    private InFlightTrade inFlight = null;

    private boolean stopped = false;
    private String stopReason = null;
    private LocalDateTime stopAt = null;

    public StreakUniqueLevelTradeDecisionMaker(
            DerivTradingService trading,
            BalanceHolder balanceHolder,
            Consumer<String> logger
    ) {
        this.trading = Objects.requireNonNull(trading, "trading");
        this.balanceHolder = Objects.requireNonNull(balanceHolder, "balanceHolder");
        this.log = Objects.requireNonNull(logger, "logger");

        // Keep recorder quiet in main log; file is created under ./trade-data
        this.recorder = new TradeHistoryRecorder(Path.of("trade-data"), s -> {});
    }

    @Override
    public void decideAndTrade(String symbol, AnalyzeContainer analyze) {
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
        int secOfMinute = ldt.getSecond();

        TradePlan plan = null;

        synchronized (this) {
            if (firstTapeEpochSecond < 0) {
                firstTapeEpochSecond = nowEpochSecond;
            }

            // 1Hz tape point
            tape.put(nowEpochSecond, level);

            // Same second: just remember "seen" and exit (keep last value for this second)
            if (nowEpochSecond == lastProcessedEpochSecond) {
                lastSeenEpochByLevel.put(level, nowEpochSecond);
                return;
            }

            // Gap -> reset streak window to avoid fake "consecutive seconds"
            if (lastProcessedEpochSecond >= 0 && (nowEpochSecond - lastProcessedEpochSecond) > 1) {
                resetWindowStateLocked();
                lastProcessedLevel = null;
            }

            boolean inWindow = isInActiveWindow(secOfMinute);

            if (!inWindow) {
                resetWindowStateLocked();
            } else {
                updateDirectionAndStreakLocked(level);
                if (directionStreak >= DIR_STREAK_MIN_SECONDS) {
                    armed = true;
                }
            }

            boolean warmedUp = isWarmedUpLocked(nowEpochSecond);
            maybeLogWarmupStateLocked(ldt, nowEpochSecond, warmedUp);

            boolean inCooldown = (cooldownUntilEpochSecond > 0 && nowEpochSecond < cooldownUntilEpochSecond);

            // IMPORTANT: read previous lastSeen BEFORE updating it with "now"
            Long prevSeen = lastSeenEpochByLevel.get(level, nowEpochSecond);

            if (warmedUp
                    && !stopped
                    && inWindow
                    && armed
                    && inFlight == null
                    && !inCooldown
                    && canTradeThisMinuteLocked(nowEpochSecond)) {

                boolean okByRepeatRule = (prevSeen == null) || (nowEpochSecond - prevSeen > LEVEL_REPEAT_MAX_AGE_SECONDS);

                if (okByRepeatRule) {
                    StakeSnapshot stake = snapshotStakeLocked();
                    int durationSeconds = oddSecondsToEndOfMinute(secOfMinute);

                    Contract contract = new Contract(
                            symbol,
                            stake.stakePerSide(),
                            durationSeconds,
                            DEFAULT_DURATION_UNIT,
                            DEFAULT_STAKE_TYPE
                    );

                    long tradeSeq = nextTradeSeq++;

                    inFlight = new InFlightTrade(tradeSeq, nowEpochSecond, symbol, stake.stakePerSide(), null);
                    lastTradeMinuteBucket = nowEpochSecond / 60;
                    armed = false;

                    plan = new TradePlan(
                            tradeSeq,
                            nowEpochSecond,
                            ldt,
                            symbol,
                            level,
                            prevSeen, // keep previous value for logging
                            contract,
                            stake,
                            durationSeconds
                    );
                }
            }

            lastProcessedEpochSecond = nowEpochSecond;
            lastProcessedLevel = level;

            // Update "seen" AFTER decision was made
            lastSeenEpochByLevel.put(level, nowEpochSecond);
        }

        if (plan != null) {
            long lastSeenAge = plan.lastSeenEpoch() == null ? -1 : (plan.epochSecond() - plan.lastSeenEpoch());

            log.accept("🟦 RULE_TRADE"
                    + " time=" + plan.ldt()
                    + " tradeSeq=" + plan.tradeSeq()
                    + " epochSecond=" + plan.epochSecond()
                    + " symbol=" + plan.symbol()
                    + " sec=" + String.format("%02d", plan.ldt().getSecond())
                    + " level=" + plan.level()
                    + " lastSeen=" + (plan.lastSeenEpoch() == null ? "NEVER" : plan.lastSeenEpoch())
                    + " lastSeenAge=" + (plan.lastSeenEpoch() == null ? "-" : (lastSeenAge + "s"))
                    + " dir=" + direction
                    + " streak=" + directionStreak
                    + " stakePerSide=" + plan.stake().stakePerSide()
                    + " ladderIdx=" + plan.stake().ladderIdxAtSend()
                    + " durationTicks=" + plan.durationSeconds() + DEFAULT_DURATION_UNIT
                    + " cooldownUntilEpoch=" + cooldownUntilEpochSecond);

            CompletableFuture<DerivTradingService.BuySellResult> fut = trading.buySellAndAwait(plan.contract());
            wireInFlightFuture(plan, fut);
        }
    }

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
        long cooldownUntil;

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
                // cooldown starts from RESULT time
                cooldownUntilEpochSecond = Math.max(cooldownUntilEpochSecond, resultEpoch + COOLDOWN_AFTER_FAIL_SECONDS);
            }

            newIdx = ladderIdx;
            cooldownUntil = cooldownUntilEpochSecond;

            if (failOnLastStep) {
                stopped = true;
                stopReason = (rootEx != null ? "LAST_STEP_ERROR->FAIL" : "LAST_STEP_FAIL");
                stopAt = ldt;
            }

            // Snapshot last 600 sec ending at RESULT time (sliding tape; no clearing)
            timelineRle = tape.snapshotLastRleJsonWithTimestamps(resultEpoch);
        }

        // Dataset record: ONE line per closed trade
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
                cooldownUntil,

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
                    + " nextStake=" + LADDER[newIdx]
                    + " cooldownUntilEpoch=" + cooldownUntil);
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
                    + " nextStake=" + LADDER[newIdx]
                    + " cooldownUntilEpoch=" + cooldownUntil);
        } else {
            log.accept("❌ RESULT"
                    + " time=" + ldt
                    + " tradeSeq=" + plan.tradeSeq()
                    + " symbol=" + plan.symbol()
                    + " res=FAIL"
                    + exText
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + LADDER[newIdx]
                    + " cooldownSec=" + COOLDOWN_AFTER_FAIL_SECONDS
                    + " cooldownUntilEpoch=" + cooldownUntil);
        }
    }

    private static Throwable unwrapCompletion(Throwable ex) {
        Throwable t = ex;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private boolean isInActiveWindow(int secOfMinute) {
        return secOfMinute >= ACTIVE_WINDOW_SEC_FROM && secOfMinute <= ACTIVE_WINDOW_SEC_TO;
    }

    private void resetWindowStateLocked() {
        direction = Direction.NONE;
        directionStreak = 0;
        armed = false;
    }

    private boolean canTradeThisMinuteLocked(long epochSecond) {
        long bucket = epochSecond / 60;
        return bucket != lastTradeMinuteBucket;
    }

    private boolean isWarmedUpLocked(long nowEpochSecond) {
        if (firstTapeEpochSecond < 0) {
            return false;
        }
        long age = nowEpochSecond - firstTapeEpochSecond;
        return age >= (MIN_HISTORY_SECONDS_BEFORE_TRADING - 1L);
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
            direction = Direction.NONE;
            directionStreak = 0;
            armed = false;
            return;
        }

        long diff = currentLevel - lastProcessedLevel;

        if (diff == 0) {
            direction = Direction.NONE;
            directionStreak = 0;
            armed = false;
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
        int idx = ladderIdx;
        BigDecimal stakePerSide = LADDER[idx];
        return new StakeSnapshot(idx, stakePerSide);
    }

    private static int oddSecondsToEndOfMinute(int nowSecond) {
        int remain = 59 - nowSecond;
        if ((remain & 1) == 1) {
            return remain;
        }
        return Math.max(1, remain - 1);
    }

    private static Long extractLastLevel(AnalyzeContainer analyze) {
        if (analyze == null) {
            return null;
        }
        AnalyzeContainer.LevelsEdgesSnapshot snap = analyze.snapshotLevelsEdges();
        if (snap == null || snap.size() <= 0) {
            return null;
        }
        return snap.last();
    }

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
            Long lastSeenEpoch,
            Contract contract,
            StakeSnapshot stake,
            int durationSeconds
    ) {}

    /**
     * TTL map for (level -> lastSeenEpoch), purged by horizon.
     * Thread-safe (CHM). Purge is throttled to once per epochSecond.
     */
    private static final class LongLongTtlMap {
        private final int horizonSec;
        private final java.util.concurrent.ConcurrentHashMap<Long, Long> map = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicLong lastPurgeEpoch = new AtomicLong(-1);

        private LongLongTtlMap(int horizonSec) {
            this.horizonSec = horizonSec;
        }

        void put(long level, long epochSecond) {
            map.put(level, epochSecond);
            maybePurge(epochSecond);
        }

        Long get(long level, long nowEpochSecond) {
            maybePurge(nowEpochSecond);
            return map.get(level);
        }

        private void maybePurge(long nowEpochSecond) {
            long prev = lastPurgeEpoch.get();
            if (prev == nowEpochSecond) return;
            if (!lastPurgeEpoch.compareAndSet(prev, nowEpochSecond)) return;

            long minEpochInclusive = nowEpochSecond - (horizonSec - 1L);
            for (var e : map.entrySet()) {
                Long lastSeen = e.getValue();
                if (lastSeen != null && lastSeen < minEpochInclusive) {
                    map.remove(e.getKey(), lastSeen);
                }
            }
        }
    }
}
