// StreakUniqueLevelTradeDecisionMaker.java
package com.zoosool.analyze;

import com.zoosool.deriv.BalanceHolder;
import com.zoosool.deriv.DerivTradingService;
import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.Contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Singleton rule-based auto-trader for stpRNG.
 *
 * Trading:
 * - Stake ladder per side (BUY-SELL, two sides).
 * - SUCCESS if DerivTradingService.BuySellResult == SUCCESS
 * - Ladder resets on SUCCESS; increases on FAIL.
 *
 * Cooldown:
 * - After any FAIL wait 2 minutes (ignore signals).
 *
 * Important fix:
 * - NO "stale inFlight release" (it causes overlapping trades and out-of-order ladder resets).
 * - cooldown starts from RESULT time (not from plan epochSecond).
 */
public final class StreakUniqueLevelTradeDecisionMaker implements TradeDecisionMaker {

    private static final String ALLOWED_SYMBOL = "stpRNG5";

    private static final BigDecimal[] LADDER = {
            BigDecimal.valueOf(1),
            BigDecimal.valueOf(12),
            BigDecimal.valueOf(160)
    };

    private static final int TAPE_KEEP_SECONDS = 60;
    private static final int MIN_HISTORY_SECONDS_BEFORE_TRADING = TAPE_KEEP_SECONDS;

    private static final int LEVEL_REPEAT_MAX_AGE_SECONDS = 30;
    private static final int DIR_STREAK_MIN_SECONDS = 3;

    private static final int ACTIVE_WINDOW_SEC_FROM = 3;
    private static final int ACTIVE_WINDOW_SEC_TO = 35;

    private static final long COOLDOWN_AFTER_FAIL_SECONDS = 300; //120L; // 2 minutes

    private static final String DEFAULT_DURATION_UNIT = "s";
    //private static final String DEFAULT_STAKE_TYPE = "payout";
    private static final String DEFAULT_STAKE_TYPE = "stake";

    private enum Direction { UP, DOWN, NONE }

    private final DerivTradingService trading;
    @SuppressWarnings("unused")
    private final BalanceHolder balanceHolder;
    private final Consumer<String> log;
    private final ZoneId zone = ZoneId.systemDefault();

    @SuppressWarnings("unused")
    private final GlobalLevelTape tape = new GlobalLevelTape(TAPE_KEEP_SECONDS);

    private final ConcurrentMap<Long, Long> lastSeenEpochByLevel = new ConcurrentHashMap<>();
    private final AtomicLong lastSeenPurgeEpoch = new AtomicLong(-1);

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
     * Even though we gate with inFlight, this makes ladder updates bulletproof.
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
    }

    @Override
    public void decideAndTrade(String symbol, AnalyzeContainer analyze) {
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
        int secOfMinute = ldt.getSecond();

        TradePlan plan = null;

        synchronized (this) {
            if (firstTapeEpochSecond < 0) {
                firstTapeEpochSecond = nowEpochSecond;
            }

            tape.put(nowEpochSecond, level);
            maybePurgeLastSeenLevels(nowEpochSecond);

            // Same second (multiple events in same epochSecond): only remember lastSeen and exit.
            if (nowEpochSecond == lastProcessedEpochSecond) {
                lastSeenEpochByLevel.put(level, nowEpochSecond);
                return;
            }

            // Gap -> reset to avoid fake "consecutive seconds"
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

            if (warmedUp
                    && !stopped
                    && inWindow
                    && armed
                    && inFlight == null
                    && !inCooldown
                    && canTradeThisMinuteLocked(nowEpochSecond)) {

                Long lastSeen = lastSeenEpochByLevel.get(level);
                boolean okByRepeatRule = (lastSeen == null) || (nowEpochSecond - lastSeen > LEVEL_REPEAT_MAX_AGE_SECONDS);

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
                            lastSeen,
                            contract,
                            stake,
                            durationSeconds
                    );
                }
            }

            lastProcessedEpochSecond = nowEpochSecond;
            lastProcessedLevel = level;

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
                    + " total=" + plan.stake().stakePerSide().multiply(BigDecimal.valueOf(2))
                    + " ladderIdx=" + plan.stake().ladderIdxAtSend()
                    + " durationTicks=" + plan.durationSeconds() + DEFAULT_DURATION_UNIT
                    + " rule=streak>=" + DIR_STREAK_MIN_SECONDS
                    + " uniqueLevelAge>" + LEVEL_REPEAT_MAX_AGE_SECONDS
                    + " window=" + String.format("%02d", ACTIVE_WINDOW_SEC_FROM) + ".." + String.format("%02d", ACTIVE_WINDOW_SEC_TO)
                    + " warmupNeedSec=" + MIN_HISTORY_SECONDS_BEFORE_TRADING
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
        LocalDateTime ldt = LocalDateTime.now(zone);

        Throwable rootEx = (ex == null) ? null : unwrapCompletion(ex);
        String exText = (rootEx == null) ? "" : (" ex=" + rootEx);

        boolean success = (rootEx == null && res == DerivTradingService.BuySellResult.SUCCESS);

        int prevIdx;
        int newIdx;
        boolean failOnLastStep;
        long cooldownUntil;

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

                // cooldown starts from RESULT time, not from plan time
                long nowSec = Instant.now().getEpochSecond();
                cooldownUntilEpochSecond = Math.max(cooldownUntilEpochSecond, nowSec + COOLDOWN_AFTER_FAIL_SECONDS);
            }

            newIdx = ladderIdx;
            cooldownUntil = cooldownUntilEpochSecond;

            if (failOnLastStep) {
                stopped = true;
                stopReason = (rootEx != null ? "LAST_STEP_ERROR->FAIL" : "LAST_STEP_FAIL");
                stopAt = ldt;
            }
        }

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

    private void maybePurgeLastSeenLevels(long nowEpochSecond) {
        long prev = lastSeenPurgeEpoch.get();
        if (prev == nowEpochSecond) {
            return;
        }
        if (!lastSeenPurgeEpoch.compareAndSet(prev, nowEpochSecond)) {
            return;
        }

        long minEpochInclusive = nowEpochSecond - (TAPE_KEEP_SECONDS - 1L);

        for (var e : lastSeenEpochByLevel.entrySet()) {
            Long lastSeen = e.getValue();
            if (lastSeen != null && lastSeen < minEpochInclusive) {
                lastSeenEpochByLevel.remove(e.getKey(), lastSeen);
            }
        }
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

    private static final class GlobalLevelTape {
        private final int keepSeconds;
        private final ConcurrentMap<Long, Long> byEpoch = new ConcurrentHashMap<>();
        private final AtomicLong lastPurgeEpoch = new AtomicLong(-1);

        private GlobalLevelTape(int keepSeconds) {
            this.keepSeconds = keepSeconds;
        }

        void put(long epochSecond, long level) {
            byEpoch.put(epochSecond, level);
            maybePurge(epochSecond);
        }

        private void maybePurge(long nowEpochSecond) {
            long prev = lastPurgeEpoch.get();
            if (prev == nowEpochSecond) {
                return;
            }
            if (!lastPurgeEpoch.compareAndSet(prev, nowEpochSecond)) {
                return;
            }

            long minEpochInclusive = nowEpochSecond - (keepSeconds - 1L);
            for (Long key : byEpoch.keySet()) {
                if (key < minEpochInclusive) {
                    byEpoch.remove(key);
                }
            }
        }
    }
}
