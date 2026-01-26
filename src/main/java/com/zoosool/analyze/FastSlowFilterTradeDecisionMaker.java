package com.zoosool.analyze;

import com.zoosool.deriv.BalanceHolder;
import com.zoosool.deriv.DerivTradingService;
import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.Contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Trading decision maker with:
 * - global tape (level per epochSecond)
 * - LEVEL_EXIT checks at :14/:44 using entry :00/:30 (entryEpoch = exitEpoch - 14)
 * - cooldown that starts ON, releases only after confirmations from LEVEL_EXIT (and regime OK)
 * - "tradeArmed" gating: after cooldown end, trading stays disarmed until N good LEVEL_EXIT in a row
 * - FAST/SLOW regime calculators to avoid toxic phases (tie/small/high-flat)
 * - HARD PAUSE + RESET if real trading starts "crooked" (first/second trade after green filters fail)
 * <p>
 * Key idea:
 * - Tape updates + LEVEL_EXIT checks ALWAYS run (even in cooldown / hard pause)
 * - Cooldown blocks only actual TRADE actions
 * - Hard pause blocks trading AND freezes cooldown/arming transitions (but still collects data)
 */
public final class FastSlowFilterTradeDecisionMaker implements TradeDecisionMaker {

    // ======== Ladder / trading timing ========

    private static final BigDecimal[] LADDER = {
            BigDecimal.valueOf(1),
            BigDecimal.valueOf(6),
            BigDecimal.valueOf(40),
            BigDecimal.valueOf(350),
    };

    // Trade moments (you click at :29/:59, actual open ~:30/:00)
    private static final int TRADE_SEC_A = 29;
    private static final int TRADE_SEC_B = 59;

    // Exit seconds for entry->exit checks: 00->14, 30->44
    private static final int EXIT_SEC_A = 14;
    private static final int EXIT_SEC_B = 44;

    // How far exit is from entry in seconds
    private static final int ENTRY_TO_EXIT_SECONDS = 14;

    // entrySec -> clickSec (entry=00 => click=59, entry=30 => click=29)
    private static int clickSecondForEntrySecond(int entrySecond) {
        int s = entrySecond - 1;
        return s < 0 ? 59 : s;
    }

    // ======== Policy: cooldown / arming / hard pause ========

    /**
     * Cooldown release:
     * - Need X consecutive WIN (exit != entry)
     * - Among them at least Y "good" moves: abs(diff) >= 2
     * - And regime must be OK (FAST and SLOW)
     * <p>
     * With 2 exit checks/min:
     * - confirm=4 => ~2 minutes best-case to release (fast enough for "random regimes")
     */
    private static final int RELEASE_CONFIRMATIONS = 4;
    private static final int RELEASE_GOOD_MIN = 2;
    private static final long GOOD_DIFF_ABS_GE = 2L;

    /**
     * After cooldown ends, we DO NOT trade immediately.
     * Require N consecutive "armable" LEVEL_EXIT:
     * - WIN (diff != 0)
     * - abs(diff) >= 2
     * - regime OK
     */
    private static final int ARM_OK_STREAK = 2;

    /**
     * Enter cooldown after N consecutive real trade FAILs.
     * Any FAIL also disarms immediately.
     */
    private static final int FAIL_STREAK_LIMIT = 2;

    /**
     * Hard pause if trading starts "crooked":
     * - If 1st or 2nd trade after becoming ARMED fails -> hard pause + reset regime
     */
    private static final int EARLY_TRADE_WINDOW = 2;

    /**
     * If in early trades we hit ladder step >= 2 (index >= 2) - it's a strong "bad start" signal.
     */
    private static final int EARLY_LADDER_BAD_STEP_IDX = 2;

    /**
     * Hard pause duration (seconds).
     */
    private static final long HARD_PAUSE_SECONDS = 300L; // 5 minutes

    /**
     * If in-flight trade hangs too long, we release the guard to avoid deadlock.
     */
    private static final long IN_FLIGHT_STALE_SECONDS = 90L;

    // ======== Profitability tracker (optional log every 5 min) ========

    private static final long PROF_WINDOW_SECONDS = 300L;

    // ======== Global concurrency guards ========

    /**
     * Ensures only ONE trade per epochSecond across all instances/threads.
     */
    private static final AtomicLong GLOBAL_LAST_TRADE_EPOCH_SECOND = new AtomicLong(-1);

    /**
     * We want: ONE processing per :14/:44 second.
     */
    private static final AtomicLong GLOBAL_LAST_EXIT_CHECK_EPOCH_SECOND = new AtomicLong(-1);

    /**
     * Global in-flight guard: allow only ONE in-flight trade in the whole process.
     */
    private static final String GLOBAL_IN_FLIGHT_KEY = "GLOBAL";
    //private static final ConcurrentMap<String, InFlightTrade> GLOBAL_IN_FLIGHT = new ConcurrentHashMap<>();

    // ======== Global state ========

    /**
     * cooldown starts ON.
     */
    private static final AtomicInteger GLOBAL_COOLDOWN_ON = new AtomicInteger(1);

    /**
     * tradeArmed gate:
     * - after cooldown end => tradeArmed=false
     * - becomes true only after ARM_OK_STREAK consecutive armable exits
     */
    private static final AtomicInteger GLOBAL_TRADE_ARMED = new AtomicInteger(0);
    private static final AtomicInteger GLOBAL_ARM_OK_STREAK = new AtomicInteger(0);

    /**
     * Fail streak from real trade results.
     */
    private static final AtomicInteger GLOBAL_FAIL_STREAK = new AtomicInteger(0);

    /**
     * Cooldown release counters (from LEVEL_EXIT).
     */
    private static final AtomicInteger GLOBAL_RELEASE_CONFIRM_STREAK = new AtomicInteger(0);
    private static final AtomicInteger GLOBAL_RELEASE_GOOD_COUNT = new AtomicInteger(0);

    /**
     * Hard pause: until epochSecond.
     * While hard paused:
     * - trading is blocked
     * - cooldown/arming transitions are frozen (but data keeps collecting)
     */
    private static final AtomicLong GLOBAL_HARD_PAUSE_UNTIL_EPOCH = new AtomicLong(0);

    /**
     * "Session start" tracking:
     * We reset this when we become TRADE_ARMED, and use it to detect "crooked start".
     */
    private static final AtomicInteger GLOBAL_SESSION_TRADE_COUNT = new AtomicInteger(0);
    private static final AtomicInteger GLOBAL_SESSION_FAIL_COUNT = new AtomicInteger(0);
    private static final AtomicLong GLOBAL_SESSION_ARMED_AT_EPOCH = new AtomicLong(0);

    /**
     * Hard stop:
     * If we lose when we are already on the last ladder step -> stop trading completely.
     */
    private static final AtomicInteger GLOBAL_STOP_ON = new AtomicInteger(0);
    @SuppressWarnings("unused")
    private static volatile String GLOBAL_STOP_REASON = null;
    @SuppressWarnings("unused")
    private static volatile LocalDateTime GLOBAL_STOP_AT = null;

    // ======== Tape ========

    /**
     * Tape: lastLevel per epochSecond.
     * Keep last ~120 seconds to cover clock jitter.
     */
    //private static final GlobalLevelTape GLOBAL_TAPE = new GlobalLevelTape(120);

    // ======== Regime calculators (FAST / SLOW) ========

    //private static final FastRegimeCalculator FAST_REGIME = new FastRegimeCalculator(new FastRegimeCalculator.Config());
    //private static final SlowRegimeCalculator SLOW_REGIME = new SlowRegimeCalculator(new SlowRegimeCalculator.Config());

    // avoid spam: log regime once per 60 seconds bucket OR on status change
    private static final AtomicLong GLOBAL_LAST_REGIME_LOG_BUCKET = new AtomicLong(-1);

    // ======== Profitability tracker (clickSec stats) ========

    //private static final GlobalSecondProfitabilityTracker GLOBAL_PROFIT_TRACKER =
    //        new GlobalSecondProfitabilityTracker(PROF_WINDOW_SECONDS);

    // ======== Instance dependencies ========

    private final DerivTradingService trading;
    private final Consumer<String> log;
    private final ZoneId zone = ZoneId.systemDefault();
    @SuppressWarnings("unused")
    private final BalanceHolder balanceHolder;

    // Progression (instance state)
    private int ladderIdx = 0;

    public FastSlowFilterTradeDecisionMaker(
            DerivTradingService trading,
            BalanceHolder balanceHolder,
            Consumer<String> logger
    ) {
        this.trading = Objects.requireNonNull(trading, "trading");
        this.log = Objects.requireNonNull(logger, "logger");
        this.balanceHolder = Objects.requireNonNull(balanceHolder, "balanceHolder");
    }

    @Override
    public void decideAndTrade(String symbol, AnalyzeContainer analyze) {
        if (true) {
            return;
        }

//        if (!isAllowedSymbol(symbol)) {
//            return;
//        }
//
//        Instant now = Instant.now();
//        LocalDateTime ldt = LocalDateTime.ofInstant(now, zone);
//        long epochSecond = now.getEpochSecond();
//        int secNow = ldt.getSecond();
//
//        // 0) ALWAYS: update tape (even in cooldown / hard pause)
//        maybeUpdateTape(epochSecond, secNow, analyze);
//
//        // 1) ALWAYS: process exit-check at :14/:44 (even in cooldown / hard pause)
//        maybeExitCheckCooldownAndRegime(symbol, ldt, epochSecond);
//
//        // 2) STOP blocks only trading (tape/checks already done above)
//        if (isStopped()) {
//            return;
//        }
//
//        // 3) HARD PAUSE blocks trading
//        if (isHardPaused(epochSecond)) {
//            return;
//        }
//
//        // 4) cooldown blocks ONLY trading (tape/checks already done)
//        if (GLOBAL_COOLDOWN_ON.get() == 1) {
//            return;
//        }
//
//        // 5) if not armed -> do not trade
//        if (GLOBAL_TRADE_ARMED.get() != 1) {
//            return;
//        }
//
//        // 6) Trade only at :29/:59
//        if (!isTradeSecond(secNow)) {
//            return;
//        }
//
//        // 7) Anti-dup per second (global)
//        if (!tryAcquireGlobalTradeSecond(epochSecond)) {
//            return;
//        }
//
//        // 8) Release stale in-flight if needed (prevents permanent blocking)
//        maybeReleaseStaleInFlight(epochSecond, ldt);
//
//        // 9) Stake + contract
//        StakeSnapshot stake = snapshotStake();
//        Contract contract = new Contract(symbol, stake.stakePerSide(), 15, "s", "stake");
//
//        // 10) Reserve global in-flight
//        if (!tryReserveInFlight(epochSecond, symbol, stake.stakePerSide())) {
//            log.accept("⏳ SKIP (in-flight) makerId=" + System.identityHashCode(this)
//                    + " symbol=" + symbol
//                    + " time=" + ldt
//                    + " epochSecond=" + epochSecond);
//            return;
//        }
//
//        // 11) Start async trade + completion handler
//        startTradeAndTrackResult(epochSecond, symbol, contract, stake.stakePerSide());
//
//        log.accept("🟦 TRADE makerId=" + System.identityHashCode(this)
//                + " thread=" + Thread.currentThread().getName()
//                + " time=" + ldt
//                + " epochSecond=" + epochSecond
//                + " symbol=" + symbol
//                + " stakePerSide=" + stake.stakePerSide()
//                + " total=" + stake.stakePerSide().multiply(BigDecimal.valueOf(2))
//                + " ladderIdx=" + stake.ladderIdxAtSend()
//                + " armed=true");
    }

//    private void enterCooldown(LocalDateTime ldt, String symbol, String reason) {
//        if (isStopped()) {
//            return;
//        }
//
//        if (GLOBAL_COOLDOWN_ON.compareAndSet(0, 1)) {
//            resetCooldownCounters();
//            disarmTrading();
//
//            // reset fail streak for safety (we'll re-enter based on new trade results)
//            GLOBAL_FAIL_STREAK.set(0);
//
//            log.accept("🛑 COOLDOWN_START makerId=" + System.identityHashCode(this)
//                    + " symbol=" + symbol
//                    + " time=" + ldt
//                    + " reason=" + reason
//                    + " releaseRule=" + RELEASE_CONFIRMATIONS + "x WIN(exit!=entry) AND >= "
//                    + RELEASE_GOOD_MIN + "x abs(diff)>=2 AND regimeOk"
//                    + " armed=false");
//        }
//    }
//
//    private void exitCooldown(LocalDateTime ldt, String symbol, String reason) {
//        if (isStopped()) {
//            return;
//        }
//
//        GLOBAL_COOLDOWN_ON.set(0);
//
//        // IMPORTANT: after cooldown end => disarm (needs arming streak again)
//        disarmTrading();
//        resetCooldownCounters();
//
//        log.accept("🟩 COOLDOWN_END makerId=" + System.identityHashCode(this)
//                + " symbol=" + symbol
//                + " time=" + ldt
//                + " reason=" + reason
//                + " armed=false (needs " + ARM_OK_STREAK + " good exits)");
//    }
//
//    private void hardPauseAndReset(LocalDateTime ldt, String symbol, String reason) {
//        if (isStopped()) {
//            return;
//        }
//
//        long nowEpoch = Instant.now().getEpochSecond();
//        long until = nowEpoch + HARD_PAUSE_SECONDS;
//
//        long prev = GLOBAL_HARD_PAUSE_UNTIL_EPOCH.get();
//        if (until > prev) {
//            GLOBAL_HARD_PAUSE_UNTIL_EPOCH.set(until);
//        }
//
//        // Put trading into safe state
//        GLOBAL_COOLDOWN_ON.set(1);
//        disarmTrading();
//        resetCooldownCounters();
//        GLOBAL_FAIL_STREAK.set(0);
//
//        // Reset regime stats so next minutes are collected fresh
//        FAST_REGIME.reset();
//        SLOW_REGIME.reset();
//        GLOBAL_PROFIT_TRACKER.reset();
//
//        // Also reset session start window
//        GLOBAL_SESSION_TRADE_COUNT.set(0);
//        GLOBAL_SESSION_FAIL_COUNT.set(0);
//        GLOBAL_SESSION_ARMED_AT_EPOCH.set(0);
//
//        log.accept("🟧 HARD_PAUSE_START makerId=" + System.identityHashCode(this)
//                + " symbol=" + symbol
//                + " time=" + ldt
//                + " seconds=" + HARD_PAUSE_SECONDS
//                + " untilEpoch=" + GLOBAL_HARD_PAUSE_UNTIL_EPOCH.get()
//                + " reason=" + reason
//                + " note=regimeReset+cooldownOn+armedOff");
//    }

    /**
     * Updates tape with the most recent level we have.
     * We never require "full" snapshot: last is valid as long as size>0.
     */
//    private void maybeUpdateTape(long epochSecond, int secNow, AnalyzeContainer analyze) {
//        if (analyze == null) {
//            return;
//        }
//        AnalyzeContainer.LevelsEdgesSnapshot snap = analyze.snapshotLevelsEdges();
//        if (snap == null) {
//            return;
//        }
//        if (snap.size() <= 0) {
//            return;
//        }
//
//        long last = snap.last();
//        GLOBAL_TAPE.put(epochSecond, last);
//
//        // log only at entry seconds (avoid spam)
//        if (secNow == 0 || secNow == 30) {
//            log.accept("🟦 LEVEL_ENTRY makerId=" + System.identityHashCode(this)
//                    + " symbol=stpRNG time=" + LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zone)
//                    + " entrySec=" + String.format("%02d", secNow)
//                    + " level=" + last
//                    + " tapeEpoch=" + epochSecond);
//        }
//    }
//
//    /**
//     * LEVEL_EXIT at :14/:44:
//     * - entryEpoch = nowEpoch - 14 (entry :00 or :30)
//     * - exitEpoch  = nowEpoch (exit :14 or :44)
//     * - win = exit != entry, tie is LOSE
//     * <p>
//     * Feeds:
//     * - Fast/Slow regime calculators (diff)
//     * - Cooldown release counters (confirm streak + good count)
//     * - Trade arming counters (after cooldown end)
//     * <p>
//     * Also:
//     * - If regime becomes BAD while trading enabled => immediate cooldown + disarm.
//     * <p>
//     * While HARD PAUSED:
//     * - We still feed calculators and log, but we DO NOT change cooldown/arming state.
//     */
//    private void maybeExitCheckCooldownAndRegime(String symbol, LocalDateTime ldt, long nowEpochSecond) {
//        int exitSec = ldt.getSecond();
//        if (exitSec != EXIT_SEC_A && exitSec != EXIT_SEC_B) {
//            return;
//        }
//
//        if (!tryAcquireExitCheckSecond(nowEpochSecond)) {
//            return;
//        }
//
//        long entryEpochSecond = nowEpochSecond - ENTRY_TO_EXIT_SECONDS;
//
//        Long entryLevel = GLOBAL_TAPE.get(entryEpochSecond);
//        Long exitLevel = GLOBAL_TAPE.get(nowEpochSecond);
//
//        int entrySec = (exitSec == EXIT_SEC_A) ? 0 : 30;
//        int clickSec = clickSecondForEntrySecond(entrySec);
//
//        if (entryLevel == null || exitLevel == null) {
//            log.accept("⏳ LEVEL_EXIT makerId=" + System.identityHashCode(this)
//                    + " symbol=" + symbol
//                    + " time=" + ldt
//                    + " clickSec=" + String.format("%02d", clickSec)
//                    + " entrySec=" + String.format("%02d", entrySec)
//                    + " exitSec=" + String.format("%02d", exitSec)
//                    + " entry=" + (entryLevel == null ? "?" : entryLevel)
//                    + " exit=" + (exitLevel == null ? "?" : exitLevel)
//                    + " reason=" + (entryLevel == null ? "NO_ENTRY_IN_TAPE" : "NO_EXIT_IN_TAPE")
//                    + " cooldown=" + (GLOBAL_COOLDOWN_ON.get() == 1)
//                    + " armed=" + (GLOBAL_TRADE_ARMED.get() == 1)
//                    + " hardPause=" + (isHardPaused(nowEpochSecond) ? "ON" : "OFF")
//                    + " note=NOT_COUNTED");
//            return;
//        }
//
//        long diff = exitLevel - entryLevel;
//        boolean win = diff != 0; // tie => lose
//        boolean goodMove = win && Math.abs(diff) >= GOOD_DIFF_ABS_GE;
//
//        // 5-min clickSec stats
//        GLOBAL_PROFIT_TRACKER.onObservation(nowEpochSecond, clickSec, win);
//        String stats = GLOBAL_PROFIT_TRACKER.maybeBuildPeriodicLog(nowEpochSecond, ldt, symbol);
//        if (stats != null) {
//            log.accept(stats);
//        }
//
//        // Feed FAST/SLOW regime
//        FastRegimeCalculator.Snapshot fast = FAST_REGIME.onObservation(nowEpochSecond, diff);
//        SlowRegimeCalculator.Snapshot slow = SLOW_REGIME.onObservation(nowEpochSecond, diff);
//
//        boolean regimeOk =
//                fast.status() == FastRegimeCalculator.Status.OK &&
//                        slow.status() == SlowRegimeCalculator.Status.OK;
//
//        // Main LEVEL_EXIT log
//        log.accept((win ? "✅" : "❌") + " LEVEL_EXIT makerId=" + System.identityHashCode(this)
//                + " symbol=" + symbol
//                + " time=" + ldt
//                + " clickSec=" + String.format("%02d", clickSec)
//                + " entrySec=" + String.format("%02d", entrySec)
//                + " exitSec=" + String.format("%02d", exitSec)
//                + " entry=" + entryLevel
//                + " exit=" + exitLevel
//                + " diff=" + diff
//                + " result=" + (win ? (goodMove ? "WIN_GOOD(abs(diff)>=2)" : "WIN_WEAK(abs(diff)==1)") : "LOSE_TIE(diff==0)")
//                + " cooldown=" + (GLOBAL_COOLDOWN_ON.get() == 1)
//                + " armed=" + (GLOBAL_TRADE_ARMED.get() == 1)
//                + " hardPause=" + (isHardPaused(nowEpochSecond) ? "ON" : "OFF")
//                + " regime=" + (regimeOk ? "OK" : "BAD"));
//
//        // Regime log (rare)
//        if (fast.statusChanged() || slow.statusChanged() || tryLogRegimeOncePer(nowEpochSecond, 60)) {
//            log.accept("📈 REGIME time=" + ldt
//                    + " fast=" + fast.status()
//                    + " f[n=" + fast.samples()
//                    + " tie=" + fmt(fast.tieRate())
//                    + " small=" + fmt(fast.smallMoveRate())
//                    + " big=" + fmt(fast.bigMoveRate())
//                    + " win=" + fmt(fast.winRate())
//                    + " r=" + fast.reason() + "]"
//                    + " slow=" + slow.status()
//                    + " s[n=" + slow.samples()
//                    + " tie=" + fmt(slow.tieRate())
//                    + " small=" + fmt(slow.smallMoveRate())
//                    + " big=" + fmt(slow.bigMoveRate())
//                    + " win=" + fmt(slow.winRate())
//                    + " r=" + slow.reason() + "]");
//        }
//
//        if (isStopped()) {
//            return;
//        }
//
//        // While hard paused: do not change cooldown/arming state.
//        if (isHardPaused(nowEpochSecond)) {
//            return;
//        }
//
//        // If regime is BAD and we are NOT in cooldown => immediate cooldown + disarm.
//        if (GLOBAL_COOLDOWN_ON.get() == 0 && !regimeOk) {
//            enterCooldown(ldt, symbol, "REGIME_BAD fast=" + fast.status() + " slow=" + slow.status());
//            return;
//        }
//
//        // ===== Cooldown release logic (only when cooldown is ON) =====
//        if (GLOBAL_COOLDOWN_ON.get() == 1) {
//            if (win) {
//                int confirm = GLOBAL_RELEASE_CONFIRM_STREAK.incrementAndGet();
//                int good = GLOBAL_RELEASE_GOOD_COUNT.get();
//                if (goodMove) {
//                    good = GLOBAL_RELEASE_GOOD_COUNT.incrementAndGet();
//                }
//
//                log.accept("🛑 COOLDOWN_CHECK makerId=" + System.identityHashCode(this)
//                        + " symbol=" + symbol
//                        + " time=" + ldt
//                        + " confirm=" + confirm + "/" + RELEASE_CONFIRMATIONS
//                        + " good=" + good + "/" + RELEASE_GOOD_MIN
//                        + " note=" + (goodMove ? "COUNTED_POSITIVE_GOOD" : "COUNTED_POSITIVE_WEAK"));
//
//                if (confirm >= RELEASE_CONFIRMATIONS && good >= RELEASE_GOOD_MIN && regimeOk) {
//                    exitCooldown(ldt, symbol, "release=confirm>=4 and good>=2 and regimeOk");
//                }
//            } else {
//                GLOBAL_RELEASE_CONFIRM_STREAK.set(0);
//                GLOBAL_RELEASE_GOOD_COUNT.set(0);
//
//                log.accept("🛑 COOLDOWN_CHECK makerId=" + System.identityHashCode(this)
//                        + " symbol=" + symbol
//                        + " time=" + ldt
//                        + " confirm=0/" + RELEASE_CONFIRMATIONS
//                        + " good=0/" + RELEASE_GOOD_MIN
//                        + " note=COUNTED_NEGATIVE_RESET");
//            }
//            return;
//        }
//
//        // ===== Trade arming logic (only when NOT in cooldown) =====
//        if (GLOBAL_TRADE_ARMED.get() != 1) {
//            if (win && goodMove && regimeOk) {
//                int s = GLOBAL_ARM_OK_STREAK.incrementAndGet();
//                log.accept("🟨 ARM_CHECK makerId=" + System.identityHashCode(this)
//                        + " symbol=" + symbol
//                        + " time=" + ldt
//                        + " okStreak=" + s + "/" + ARM_OK_STREAK
//                        + " note=COUNTED_POSITIVE");
//                if (s >= ARM_OK_STREAK) {
//                    GLOBAL_TRADE_ARMED.set(1);
//                    GLOBAL_ARM_OK_STREAK.set(0);
//
//                    // Start a new "session start" window
//                    GLOBAL_SESSION_TRADE_COUNT.set(0);
//                    GLOBAL_SESSION_FAIL_COUNT.set(0);
//                    GLOBAL_SESSION_ARMED_AT_EPOCH.set(nowEpochSecond);
//
//                    log.accept("🟩 TRADE_ARMED makerId=" + System.identityHashCode(this)
//                            + " symbol=" + symbol
//                            + " time=" + ldt
//                            + " rule=" + ARM_OK_STREAK + "x (WIN && abs(diff)>=2 && regimeOk)"
//                            + " sessionStart=ON");
//                }
//            } else if (!win) {
//                GLOBAL_ARM_OK_STREAK.set(0);
//                log.accept("🟨 ARM_CHECK makerId=" + System.identityHashCode(this)
//                        + " symbol=" + symbol
//                        + " time=" + ldt
//                        + " okStreak=0/" + ARM_OK_STREAK
//                        + " note=RESET_ON_TIE");
//            } else {
//                GLOBAL_ARM_OK_STREAK.set(0);
//                log.accept("🟨 ARM_CHECK makerId=" + System.identityHashCode(this)
//                        + " symbol=" + symbol
//                        + " time=" + ldt
//                        + " okStreak=0/" + ARM_OK_STREAK
//                        + " note=WIN_BUT_NOT_ARMABLE");
//            }
//        }
//    }
//
//    private void enterStop(LocalDateTime ldt, String symbol, String reason) {
//        if (GLOBAL_STOP_ON.compareAndSet(0, 1)) {
//            GLOBAL_STOP_REASON = reason;
//            GLOBAL_STOP_AT = ldt;
//
//            // disable everything
//            GLOBAL_COOLDOWN_ON.set(0);
//            disarmTrading();
//            resetCooldownCounters();
//            GLOBAL_FAIL_STREAK.set(0);
//
//            log.accept("🟥 STOP_TRADING makerId=" + System.identityHashCode(this)
//                    + " symbol=" + symbol
//                    + " time=" + ldt
//                    + " reason=" + reason);
//        }
//    }
//
//    private void resetCooldownCounters() {
//        GLOBAL_RELEASE_CONFIRM_STREAK.set(0);
//        GLOBAL_RELEASE_GOOD_COUNT.set(0);
//    }
//
//    private void disarmTrading() {
//        GLOBAL_TRADE_ARMED.set(0);
//        GLOBAL_ARM_OK_STREAK.set(0);
//    }
//
//    private boolean isStopped() {
//        return GLOBAL_STOP_ON.get() == 1;
//    }
//
//    private boolean isAllowedSymbol(String symbol) {
//        return "stpRNG".equals(symbol);
//    }
//
//    private boolean isTradeSecond(int sec) {
//        return sec == TRADE_SEC_A || sec == TRADE_SEC_B;
//    }
//
//    private boolean isHardPaused(long nowEpochSecond) {
//        return nowEpochSecond < GLOBAL_HARD_PAUSE_UNTIL_EPOCH.get();
//    }
//
//    // ======== Trade execution / results ========
//
//    private StakeSnapshot snapshotStake() {
//        synchronized (this) {
//            int idx = ladderIdx;
//            BigDecimal stakePerSide = LADDER[idx];
//            return new StakeSnapshot(idx, stakePerSide);
//        }
//    }
//
//    private static boolean tryAcquireGlobalTradeSecond(long epochSecond) {
//        while (true) {
//            long prev = GLOBAL_LAST_TRADE_EPOCH_SECOND.get();
//            if (prev == epochSecond) {
//                return false;
//            }
//            if (GLOBAL_LAST_TRADE_EPOCH_SECOND.compareAndSet(prev, epochSecond)) {
//                return true;
//            }
//        }
//    }
//
//    private static boolean tryAcquireExitCheckSecond(long epochSecond) {
//        while (true) {
//            long prev = GLOBAL_LAST_EXIT_CHECK_EPOCH_SECOND.get();
//            if (prev == epochSecond) {
//                return false;
//            }
//            if (GLOBAL_LAST_EXIT_CHECK_EPOCH_SECOND.compareAndSet(prev, epochSecond)) {
//                return true;
//            }
//        }
//    }
//
//    private void maybeReleaseStaleInFlight(long nowEpochSecond, LocalDateTime ldt) {
//        InFlightTrade cur = GLOBAL_IN_FLIGHT.get(GLOBAL_IN_FLIGHT_KEY);
//        if (cur == null) {
//            return;
//        }
//        long age = nowEpochSecond - cur.epochSecond();
//        if (age <= IN_FLIGHT_STALE_SECONDS) {
//            return;
//        }
//        if (GLOBAL_IN_FLIGHT.remove(GLOBAL_IN_FLIGHT_KEY, cur)) {
//            log.accept("⚠️ IN_FLIGHT_STALE_RELEASE makerId=" + System.identityHashCode(this)
//                    + " symbol=" + cur.symbol()
//                    + " time=" + ldt
//                    + " ageSec=" + age
//                    + " note=releasedToAvoidDeadlock");
//        }
//    }
//
//    private boolean tryReserveInFlight(long epochSecond, String symbol, BigDecimal stakePerSide) {
//        InFlightTrade placeholder = new InFlightTrade(epochSecond, symbol, stakePerSide, null);
//        return GLOBAL_IN_FLIGHT.putIfAbsent(GLOBAL_IN_FLIGHT_KEY, placeholder) == null;
//    }
//
//    private void startTradeAndTrackResult(
//            long epochSecond,
//            String symbol,
//            Contract contract,
//            BigDecimal stakePerSide
//    ) {
//        CompletableFuture<DerivTradingService.BothBuyStatus> fut = trading.buyBothAndWaitAsync(contract);
//
//        InFlightTrade running = new InFlightTrade(epochSecond, symbol, stakePerSide, fut);
//        GLOBAL_IN_FLIGHT.put(GLOBAL_IN_FLIGHT_KEY, running);
//
//        fut.whenComplete((status, ex) -> {
//            try {
//                applyResult(symbol, status, ex);
//            } finally {
//                GLOBAL_IN_FLIGHT.remove(GLOBAL_IN_FLIGHT_KEY, running);
//            }
//        });
//    }
//
//    private void applyResult(String symbol, DerivTradingService.BothBuyStatus status, Throwable ex) {
//        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), zone);
//
//        if (isStopped()) {
//            log.accept("🟥 RESULT (ignored, stopped) makerId=" + System.identityHashCode(this)
//                    + " symbol=" + symbol
//                    + " status=" + ((ex != null) ? "ERROR" : (status != null && status.isSuccess() ? "SUCCESS" : "FAIL"))
//                    + " err=" + (ex != null ? ex : "null"));
//            return;
//        }
//
//        boolean success = (ex == null) && status != null && status.isSuccess();
//
//        int prevIdx;
//        int newIdx;
//        boolean failOnLastStep;
//
//        synchronized (this) {
//            prevIdx = ladderIdx;
//            failOnLastStep = (!success) && (prevIdx == LADDER.length - 1);
//
//            if (success) {
//                ladderIdx = 0;
//            } else {
//                ladderIdx = Math.min(ladderIdx + 1, LADDER.length - 1);
//            }
//            newIdx = ladderIdx;
//        }
//
//        if (failOnLastStep) {
//            String reason = (ex != null ? "LAST_STEP_ERROR->FAIL" : "LAST_STEP_FAIL");
//            enterStop(now, symbol, reason);
//
//            log.accept("❌ RESULT makerId=" + System.identityHashCode(this)
//                    + " symbol=" + symbol
//                    + " status=" + (ex != null ? "ERROR->FAIL" : "FAIL")
//                    + " ladder " + prevIdx + "->" + newIdx
//                    + " nextStake=" + LADDER[newIdx]
//                    + " NOTE=STOPPED_AFTER_LAST_STEP"
//                    + (ex != null ? " err=" + ex : ""));
//            return;
//        }
//
//        // Track session start behavior (crooked starts)
//        boolean sessionActive = (GLOBAL_SESSION_ARMED_AT_EPOCH.get() > 0);
//        int tradeNumInSession = -1;
//        if (sessionActive) {
//            tradeNumInSession = GLOBAL_SESSION_TRADE_COUNT.incrementAndGet(); // 1..N
//        }
//
//        if (success) {
//            GLOBAL_FAIL_STREAK.set(0);
//
//            log.accept("✅ RESULT makerId=" + System.identityHashCode(this)
//                    + " symbol=" + symbol
//                    + " status=SUCCESS"
//                    + " ladder " + prevIdx + "->" + newIdx
//                    + " nextStake=" + LADDER[newIdx]
//                    + " failStreak=0"
//                    + (sessionActive ? " sessionTrade=" + tradeNumInSession : ""));
//
//            return;
//        }
//
//        // FAIL:
//        // 1) disarm immediately
//        disarmTrading();
//
//        int fails = GLOBAL_FAIL_STREAK.incrementAndGet();
//        if (sessionActive) {
//            GLOBAL_SESSION_FAIL_COUNT.incrementAndGet();
//        }
//
//        log.accept("❌ RESULT makerId=" + System.identityHashCode(this)
//                + " symbol=" + symbol
//                + " status=" + (ex != null ? "ERROR->FAIL" : "FAIL")
//                + " ladder " + prevIdx + "->" + newIdx
//                + " nextStake=" + LADDER[newIdx]
//                + " failStreak=" + fails
//                + " armed=false"
//                + (sessionActive ? " sessionTrade=" + tradeNumInSession : "")
//                + (ex != null ? " err=" + ex : ""));
//
//        // HARD PAUSE + RESET if the start is crooked:
//        if (sessionActive && tradeNumInSession >= 1 && tradeNumInSession <= EARLY_TRADE_WINDOW) {
//            boolean badStepEarly = (newIdx >= EARLY_LADDER_BAD_STEP_IDX);
//            String reason = "EARLY_FAIL trade=" + tradeNumInSession
//                    + "/" + EARLY_TRADE_WINDOW
//                    + (badStepEarly ? " ladderEscalatedToIdx=" + newIdx : "")
//                    + " note=firstTradesAfterGreenShouldWinMoreOften";
//            hardPauseAndReset(now, symbol, reason);
//            return;
//        }
//
//        // Normal cooldown on consecutive fails
//        if (fails >= FAIL_STREAK_LIMIT) {
//            enterCooldown(now, symbol, (ex != null ? "TRADE_ERROR->FAIL" : "TRADE_FAIL"));
//        }
//    }
//
//    private record StakeSnapshot(int ladderIdxAtSend, BigDecimal stakePerSide) {
//    }
//
//    private record InFlightTrade(
//            long epochSecond,
//            String symbol,
//            BigDecimal stakePerSide,
//            CompletableFuture<DerivTradingService.BothBuyStatus> future
//    ) {
//    }
//
//    // ======== Tape ========
//
//    private static final class GlobalLevelTape {
//        private final int keepSeconds;
//        private final ConcurrentMap<Long, Long> byEpoch = new ConcurrentHashMap<>();
//        private final AtomicLong lastPurgeEpoch = new AtomicLong(-1);
//
//        private GlobalLevelTape(int keepSeconds) {
//            this.keepSeconds = keepSeconds;
//        }
//
//        void put(long epochSecond, long lastLevel) {
//            byEpoch.put(epochSecond, lastLevel);
//            maybePurge(epochSecond);
//        }
//
//        Long get(long epochSecond) {
//            return byEpoch.get(epochSecond);
//        }
//
//        private void maybePurge(long nowEpochSecond) {
//            long prev = lastPurgeEpoch.get();
//            if (prev == nowEpochSecond) {
//                return;
//            }
//            if (!lastPurgeEpoch.compareAndSet(prev, nowEpochSecond)) {
//                return;
//            }
//
//            long minEpochInclusive = nowEpochSecond - (keepSeconds - 1);
//            for (Long key : byEpoch.keySet()) {
//                if (key < minEpochInclusive) {
//                    byEpoch.remove(key);
//                }
//            }
//        }
//    }
//
//    // ======== Profitability tracker (clickSec stats) ========
//
//    private static final class GlobalSecondProfitabilityTracker {
//
//        private static final int SECONDS = 60;
//
//        private final AtomicLong lastRecordedEpochSecond = new AtomicLong(-1);
//        private final AtomicLong lastLoggedBucket = new AtomicLong(-1);
//
//        private final long windowSeconds;
//
//        private final int[] wins = new int[SECONDS];
//        private final int[] samples = new int[SECONDS];
//
//        private final ArrayDeque<Event> events;
//
//        private GlobalSecondProfitabilityTracker(long windowSeconds) {
//            this.windowSeconds = windowSeconds;
//            this.events = new ArrayDeque<>((int) Math.max(120, windowSeconds * 2));
//        }
//
//        void onObservation(long epochSecond, int clickSecondOfMinute, boolean win) {
//            if (!tryAcquireRecordSecond(epochSecond)) {
//                return;
//            }
//
//            synchronized (this) {
//                purgeOld(epochSecond);
//
//                events.addLast(new Event(epochSecond, clickSecondOfMinute, win));
//                samples[clickSecondOfMinute]++;
//                if (win) {
//                    wins[clickSecondOfMinute]++;
//                }
//
//                purgeOld(epochSecond);
//            }
//        }
//
//        void reset() {
//            synchronized (this) {
//                events.clear();
//                Arrays.fill(wins, 0);
//                Arrays.fill(samples, 0);
//                lastRecordedEpochSecond.set(-1);
//                lastLoggedBucket.set(-1);
//            }
//        }
//
//        String maybeBuildPeriodicLog(long epochSecond, LocalDateTime ldt, String symbol) {
//            long bucket = epochSecond / windowSeconds;
//            while (true) {
//                long prev = lastLoggedBucket.get();
//                if (prev == bucket) {
//                    return null;
//                }
//                if (lastLoggedBucket.compareAndSet(prev, bucket)) {
//                    break;
//                }
//            }
//
//            int[] winsSnap;
//            int[] samplesSnap;
//            int totalSamples;
//            synchronized (this) {
//                purgeOld(epochSecond);
//                winsSnap = Arrays.copyOf(wins, wins.length);
//                samplesSnap = Arrays.copyOf(samples, samples.length);
//                totalSamples = events.size();
//            }
//
//            final int minSamples = 3;
//
//            int[] secs = new int[SECONDS];
//            int secsCount = 0;
//            for (int s = 0; s < SECONDS; s++) {
//                if (samplesSnap[s] >= minSamples) {
//                    secs[secsCount++] = s;
//                }
//            }
//
//            Integer[] secsBoxed = new Integer[secsCount];
//            for (int i = 0; i < secsCount; i++) {
//                secsBoxed[i] = secs[i];
//            }
//
//            Arrays.sort(secsBoxed, Comparator
//                    .<Integer>comparingDouble(s -> {
//                        int smp = samplesSnap[s];
//                        return smp == 0 ? 0.0 : ((double) winsSnap[s] / (double) smp);
//                    }).reversed()
//                    .thenComparingInt(s -> winsSnap[s]).reversed()
//                    .thenComparingInt(s -> samplesSnap[s]).reversed()
//                    .thenComparingInt(s -> s)
//            );
//
//            StringBuilder sb = new StringBuilder(512);
//            sb.append("📊 5MIN_CLICK_SEC_STATS")
//                    .append(" symbol=").append(symbol)
//                    .append(" time=").append(ldt)
//                    .append(" winRule=(exit!=entry) tie=LOSE")
//                    .append(" windowSec=").append(windowSeconds)
//                    .append(" totalSamples=").append(totalSamples)
//                    .append(" note=attributedToClickSecond(entry-1)");
//
//            sb.append("\n  PERFECT:");
//            boolean anyPerfect = false;
//            for (int s = 0; s < SECONDS; s++) {
//                int smp = samplesSnap[s];
//                if (smp >= minSamples && winsSnap[s] == smp) {
//                    anyPerfect = true;
//                    sb.append(' ').append(String.format("%02d", s))
//                            .append('(').append(winsSnap[s]).append('/').append(smp).append(')');
//                }
//            }
//            if (!anyPerfect) {
//                sb.append(" none");
//            }
//
//            sb.append("\n  TOP:");
//            int topN = Math.min(10, secsBoxed.length);
//            if (topN == 0) {
//                sb.append(" none (not enough data)");
//            } else {
//                for (int i = 0; i < topN; i++) {
//                    int s = secsBoxed[i];
//                    int w = winsSnap[s];
//                    int smp = samplesSnap[s];
//                    double wr = smp == 0 ? 0.0 : (100.0 * w / smp);
//                    sb.append(' ').append(String.format("%02d", s))
//                            .append('(').append(w).append('/').append(smp)
//                            .append('=').append(String.format("%.0f", wr)).append("%)");
//                }
//            }
//
//            return sb.toString();
//        }
//
//        private boolean tryAcquireRecordSecond(long epochSecond) {
//            while (true) {
//                long prev = lastRecordedEpochSecond.get();
//                if (prev == epochSecond) {
//                    return false;
//                }
//                if (lastRecordedEpochSecond.compareAndSet(prev, epochSecond)) {
//                    return true;
//                }
//            }
//        }
//
//        private void purgeOld(long nowEpochSecond) {
//            long minEpochInclusive = nowEpochSecond - (windowSeconds - 1);
//            while (!events.isEmpty()) {
//                Event e = events.peekFirst();
//                if (e.epochSecond >= minEpochInclusive) {
//                    break;
//                }
//                events.removeFirst();
//                samples[e.clickSecondOfMinute]--;
//                if (e.win) {
//                    wins[e.clickSecondOfMinute]--;
//                }
//            }
//        }
//
//        private record Event(long epochSecond, int clickSecondOfMinute, boolean win) {
//        }
//    }
//
//    // ======== Regime calculators (FAST / SLOW) ========
//
//    private static final class FastRegimeCalculator {
//
//        public enum Status {NOT_ENOUGH_DATA, OK, BAD}
//
//        public record Snapshot(
//                long epochSecond,
//                int samples,
//                double tieRate,
//                double smallMoveRate,
//                double bigMoveRate,
//                double winRate,
//                Status status,
//                String reason,
//                boolean statusChanged
//        ) {
//        }
//
//        public static final class Config {
//            // LEVEL_EXIT happens 2 times per minute. 6..10 samples => ~3..5 minutes window.
//            public int maxSamples = 10;
//            public int minSamples = 6;
//
//            public long smallAbsLe = 1; // abs(diff) <= 1
//            public long bigAbsGe = 2;   // abs(diff) >= 2
//
//            // stricter "toxic" detection (fast reacts)
//            public double enterBadTieRate = 0.35;
//            public double enterBadSmallRate = 0.70;
//            public double enterBadWinRate = 0.70;
//
//            // hysteresis to recover
//            public double exitBadTieRate = 0.25;
//            public double exitBadSmallRate = 0.55;
//            public double exitBadWinRate = 0.80;
//
//            public double minBigMoveRateForOk = 0.20;
//        }
//
//        private final Config cfg;
//        private final ArrayDeque<Long> diffs;
//        private Status lastStatus = Status.NOT_ENOUGH_DATA;
//
//        FastRegimeCalculator(Config cfg) {
//            this.cfg = Objects.requireNonNull(cfg, "cfg");
//            this.diffs = new ArrayDeque<>(cfg.maxSamples + 2);
//        }
//
//        Snapshot onObservation(long epochSecond, long diff) {
//            diffs.addLast(diff);
//            while (diffs.size() > cfg.maxSamples) {
//                diffs.removeFirst();
//            }
//
//            int n = diffs.size();
//            Rates r = computeRatesSafe();
//
//            if (n < cfg.minSamples) {
//                return new Snapshot(epochSecond, n, r.tieRate, r.smallMoveRate, r.bigMoveRate, r.winRate,
//                        Status.NOT_ENOUGH_DATA, "needMoreData", false);
//            }
//
//            Status newStatus = decideStatus(r);
//            boolean changed = (newStatus != lastStatus);
//            lastStatus = newStatus;
//
//            return new Snapshot(epochSecond, n, r.tieRate, r.smallMoveRate, r.bigMoveRate, r.winRate,
//                    newStatus, r.reason, changed);
//        }
//
//        void reset() {
//            diffs.clear();
//            lastStatus = Status.NOT_ENOUGH_DATA;
//        }
//
//        private Status decideStatus(Rates r) {
//            if (lastStatus == Status.BAD) {
//                boolean stillBad =
//                        (r.tieRate > cfg.exitBadTieRate) ||
//                                (r.smallMoveRate > cfg.exitBadSmallRate) ||
//                                (r.winRate < cfg.exitBadWinRate);
//                r.reason = stillBad ? r.reason : "recovered";
//                return stillBad ? Status.BAD : Status.OK;
//            }
//
//            boolean bad =
//                    (r.tieRate >= cfg.enterBadTieRate) ||
//                            (r.smallMoveRate >= cfg.enterBadSmallRate) ||
//                            (r.winRate <= cfg.enterBadWinRate);
//
//            if (bad) {
//                return Status.BAD;
//            }
//
//            if (r.bigMoveRate < cfg.minBigMoveRateForOk) {
//                r.reason = "tooFlat(bigMoveRateLow)";
//                return Status.BAD;
//            }
//
//            r.reason = "ok";
//            return Status.OK;
//        }
//
//        private Rates computeRatesSafe() {
//            if (diffs.isEmpty()) return new Rates(0, 0, 0, 0, "");
//            return computeRates();
//        }
//
//        private Rates computeRates() {
//            int n = diffs.size();
//            int tie = 0, small = 0, big = 0, win = 0;
//
//            for (Long d : diffs) {
//                long x = d;
//                if (x == 0) tie++;
//                if (Math.abs(x) <= cfg.smallAbsLe) small++;
//                if (Math.abs(x) >= cfg.bigAbsGe) big++;
//                if (x != 0) win++;
//            }
//
//            Rates r = new Rates(
//                    ((double) tie) / n,
//                    ((double) small) / n,
//                    ((double) big) / n,
//                    ((double) win) / n,
//                    ""
//            );
//
//            if (r.tieRate >= cfg.enterBadTieRate) r.reason = "tieRateHigh";
//            else if (r.smallMoveRate >= cfg.enterBadSmallRate) r.reason = "smallMoveRateHigh";
//            else if (r.winRate <= cfg.enterBadWinRate) r.reason = "winRateLow";
//            else r.reason = "okCandidate";
//
//            return r;
//        }
//
//        private static final class Rates {
//            double tieRate;
//            double smallMoveRate;
//            double bigMoveRate;
//            double winRate;
//            String reason;
//
//            Rates(double tieRate, double smallMoveRate, double bigMoveRate, double winRate, String reason) {
//                this.tieRate = tieRate;
//                this.smallMoveRate = smallMoveRate;
//                this.bigMoveRate = bigMoveRate;
//                this.winRate = winRate;
//                this.reason = reason;
//            }
//        }
//    }
//
//    private static final class SlowRegimeCalculator {
//
//        public enum Status {NOT_ENOUGH_DATA, OK, BAD}
//
//        public record Snapshot(
//                long epochSecond,
//                int samples,
//                double tieRate,
//                double smallMoveRate,
//                double bigMoveRate,
//                double winRate,
//                Status status,
//                String reason,
//                boolean statusChanged
//        ) {
//        }
//
//        public static final class Config {
//            // Not "1 hour warmup": 10 minutes window, minSamples ~6 minutes
//            public long windowSeconds = 600; // 10 min
//            public int minSamples = 12;      // ~6 min (2 exits/min)
//
//            public long smallAbsLe = 1;
//            public long bigAbsGe = 2;
//
//            // stricter toxic detection, but slower than FAST
//            public double enterBadTieRate = 0.30;
//            public double enterBadSmallRate = 0.65;
//            public double enterBadWinRate = 0.75;
//
//            public double exitBadTieRate = 0.20;
//            public double exitBadSmallRate = 0.50;
//            public double exitBadWinRate = 0.82;
//
//            public double minBigMoveRateForOk = 0.15;
//        }
//
//        private record Event(long epochSecond, long diff) {
//        }
//
//        private final Config cfg;
//        private final ArrayDeque<Event> events = new ArrayDeque<>(256);
//        private Status lastStatus = Status.NOT_ENOUGH_DATA;
//
//        SlowRegimeCalculator(Config cfg) {
//            this.cfg = Objects.requireNonNull(cfg, "cfg");
//        }
//
//        Snapshot onObservation(long epochSecond, long diff) {
//            events.addLast(new Event(epochSecond, diff));
//            purge(epochSecond);
//
//            int n = events.size();
//            Rates r = computeRates();
//
//            if (n < cfg.minSamples) {
//                return new Snapshot(epochSecond, n, r.tieRate, r.smallMoveRate, r.bigMoveRate, r.winRate,
//                        Status.NOT_ENOUGH_DATA, "needMoreData", false);
//            }
//
//            Status newStatus = decideStatus(r);
//            boolean changed = (newStatus != lastStatus);
//            lastStatus = newStatus;
//
//            return new Snapshot(epochSecond, n, r.tieRate, r.smallMoveRate, r.bigMoveRate, r.winRate,
//                    newStatus, r.reason, changed);
//        }
//
//        void reset() {
//            events.clear();
//            lastStatus = Status.NOT_ENOUGH_DATA;
//        }
//
//        private void purge(long nowEpochSecond) {
//            long minEpoch = nowEpochSecond - (cfg.windowSeconds - 1);
//            while (!events.isEmpty() && events.peekFirst().epochSecond < minEpoch) {
//                events.removeFirst();
//            }
//        }
//
//        private Status decideStatus(Rates r) {
//            if (lastStatus == Status.BAD) {
//                boolean stillBad =
//                        (r.tieRate > cfg.exitBadTieRate) ||
//                                (r.smallMoveRate > cfg.exitBadSmallRate) ||
//                                (r.winRate < cfg.exitBadWinRate);
//                r.reason = stillBad ? r.reason : "recovered";
//                return stillBad ? Status.BAD : Status.OK;
//            }
//
//            boolean bad =
//                    (r.tieRate >= cfg.enterBadTieRate) ||
//                            (r.smallMoveRate >= cfg.enterBadSmallRate) ||
//                            (r.winRate <= cfg.enterBadWinRate);
//
//            if (bad) {
//                return Status.BAD;
//            }
//
//            if (r.bigMoveRate < cfg.minBigMoveRateForOk) {
//                r.reason = "tooFlat(bigMoveRateLow)";
//                return Status.BAD;
//            }
//
//            r.reason = "ok";
//            return Status.OK;
//        }
//
//        private Rates computeRates() {
//            int n = events.size();
//            if (n == 0) return new Rates(0, 0, 0, 0, "");
//
//            int tie = 0, small = 0, big = 0, win = 0;
//
//            for (Event e : events) {
//                long x = e.diff;
//                if (x == 0) tie++;
//                if (Math.abs(x) <= cfg.smallAbsLe) small++;
//                if (Math.abs(x) >= cfg.bigAbsGe) big++;
//                if (x != 0) win++;
//            }
//
//            Rates r = new Rates(
//                    ((double) tie) / n,
//                    ((double) small) / n,
//                    ((double) big) / n,
//                    ((double) win) / n,
//                    ""
//            );
//
//            if (r.tieRate >= cfg.enterBadTieRate) r.reason = "tieRateHigh";
//            else if (r.smallMoveRate >= cfg.enterBadSmallRate) r.reason = "smallMoveRateHigh";
//            else if (r.winRate <= cfg.enterBadWinRate) r.reason = "winRateLow";
//            else r.reason = "okCandidate";
//
//            return r;
//        }
//
//        private static final class Rates {
//            double tieRate;
//            double smallMoveRate;
//            double bigMoveRate;
//            double winRate;
//            String reason;
//
//            Rates(double tieRate, double smallMoveRate, double bigMoveRate, double winRate, String reason) {
//                this.tieRate = tieRate;
//                this.smallMoveRate = smallMoveRate;
//                this.bigMoveRate = bigMoveRate;
//                this.winRate = winRate;
//                this.reason = reason;
//            }
//        }
//    }
//
//    // ======== Small helpers ========
//
//    private static boolean tryLogRegimeOncePer(long nowEpochSecond, long periodSeconds) {
//        long bucket = nowEpochSecond / periodSeconds;
//        while (true) {
//            long prev = GLOBAL_LAST_REGIME_LOG_BUCKET.get();
//            if (prev == bucket) return false;
//            if (GLOBAL_LAST_REGIME_LOG_BUCKET.compareAndSet(prev, bucket)) return true;
//        }
//    }
//
//    private static String fmt(double v) {
//        return String.format("%.2f", v);
//    }
}
