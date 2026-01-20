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

public final class NoFilterTradeDecisionMaker implements TradeDecisionMaker {

    private static final BigDecimal[] LADDER = {
            BigDecimal.valueOf(1),
            BigDecimal.valueOf(6),
            BigDecimal.valueOf(40),
            BigDecimal.valueOf(350),
    };

    // Trade moments (you click at :29/:59, actual open ~:30/:00)
    private static final int TRADE_SEC_A = 29;
    private static final int TRADE_SEC_B = 59;

    /**
     * NEW cooldown release logic:
     * - Need 6 consecutive confirmations (WIN = exit != entry)
     * - Among those 6, at least 3 must be "good": abs(diff) > 1
     */
    private static final int RELEASE_CONFIRMATIONS = 2;
    private static final int RELEASE_GOOD_MIN = 2;
    private static final long GOOD_DIFF_ABS_GT = 1L;

    /**
     * After 2 consecutive trade fails, enter cooldown again.
     * (kept as-is)
     */
    private static final int FAIL_STREAK_LIMIT = 4;

    /**
     * Exit seconds for entry->exit checks:
     * - 00 -> 14 (exit :14)
     * - 30 -> 44 (exit :44)
     */
    private static final int EXIT_SEC_A = 14;
    private static final int EXIT_SEC_B = 44;

    /**
     * How far exit is from entry in seconds.
     * :14 - :00 = 14, :44 - :30 = 14
     */
    private static final int ENTRY_TO_EXIT_SECONDS = 14;

    /**
     * For stats: click second is "one second before entry".
     * entry=00 -> click=59
     * entry=30 -> click=29
     */
    private static int clickSecondForEntrySecond(int entrySecond) {
        int s = entrySecond - 1;
        return s < 0 ? 59 : s;
    }

    /**
     * Profitability tracker logs once per 5 minutes.
     */
    private static final long PROF_WINDOW_SECONDS = 300L;

    /**
     * Ensures only ONE trade per epochSecond across all instances/threads.
     */
    private static final AtomicLong GLOBAL_LAST_TRADE_EPOCH_SECOND = new AtomicLong(-1);

    /**
     * Global in-flight guard: we intentionally allow only ONE in-flight trade in the whole process.
     */
    private static final String GLOBAL_IN_FLIGHT_KEY = "GLOBAL";
    private static final ConcurrentMap<String, InFlightTrade> GLOBAL_IN_FLIGHT = new ConcurrentHashMap<>();

    /**
     * If an in-flight trade "hangs" too long (no completion), we release it to avoid deadlock.
     * This does NOT change ladder/cooldown; it only prevents permanent blocking.
     */
    private static final long IN_FLIGHT_STALE_SECONDS = 90L;

    /**
     * Global fail/cooldown state.
     *
     * IMPORTANT: cooldown starts as ON.
     */
    private static final AtomicInteger GLOBAL_FAIL_STREAK = new AtomicInteger(0);
    private static final AtomicInteger GLOBAL_COOLDOWN_ON = new AtomicInteger(1); // 0/1 (DEFAULT ON)

    /**
     * NEW: cooldown release counters.
     * - confirmations: consecutive WIN (exit != entry)
     * - goodCount: within the current consecutive sequence, how many have abs(diff) > 1
     *
     * Reset conditions:
     * - any LOSE (tie) => confirmations=0, goodCount=0
     * - NOT_COUNTED (no tape data) => do not change anything
     */
    private static final AtomicInteger GLOBAL_RELEASE_CONFIRM_STREAK = new AtomicInteger(0);
    private static final AtomicInteger GLOBAL_RELEASE_GOOD_COUNT = new AtomicInteger(0);

    /**
     * Hard stop:
     * If we lose when we are already on the last ladder step -> stop trading completely.
     */
    private static final AtomicInteger GLOBAL_STOP_ON = new AtomicInteger(0); // 0/1
    @SuppressWarnings("unused")
    private static volatile String GLOBAL_STOP_REASON = null;
    @SuppressWarnings("unused")
    private static volatile LocalDateTime GLOBAL_STOP_AT = null;

    /**
     * Global gate for exit-check execution/logging (avoid duplicates from multiple pipelines/threads).
     * We want: ONE processing per :14/:44 second.
     */
    private static final AtomicLong GLOBAL_LAST_EXIT_CHECK_EPOCH_SECOND = new AtomicLong(-1);

    /**
     * Tape: lastLevel per epochSecond.
     * We keep last ~120 seconds to cover rotations/clock jitter safely.
     */
    private static final GlobalLevelTape GLOBAL_TAPE = new GlobalLevelTape(120);

    /**
     * Profitability tracker based on entry->exit win rule.
     * We attribute result to clickSecond (59/29/..).
     */
    private static final GlobalSecondProfitabilityTracker GLOBAL_PROFIT_TRACKER =
            new GlobalSecondProfitabilityTracker(PROF_WINDOW_SECONDS);

    private static boolean tryAcquireGlobalTradeSecond(long epochSecond) {
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

    private static boolean tryAcquireExitCheckSecond(long epochSecond) {
        while (true) {
            long prev = GLOBAL_LAST_EXIT_CHECK_EPOCH_SECOND.get();
            if (prev == epochSecond) {
                return false;
            }
            if (GLOBAL_LAST_EXIT_CHECK_EPOCH_SECOND.compareAndSet(prev, epochSecond)) {
                return true;
            }
        }
    }

    private final DerivTradingService trading;
    private final Consumer<String> log;
    private final ZoneId zone = ZoneId.systemDefault();
    private final BalanceHolder balanceHolder;

    // Progression (instance state)
    private int ladderIdx = 0;

    public NoFilterTradeDecisionMaker(DerivTradingService trading, BalanceHolder balanceHolder, Consumer<String> logger) {
        this.trading = Objects.requireNonNull(trading, "trading");
        this.log = Objects.requireNonNull(logger, "logger");
        this.balanceHolder = Objects.requireNonNull(balanceHolder, "balanceHolder");
    }

    @Override
    public void decideAndTrade(String symbol, AnalyzeContainer analyze) {
//        if (true) {
//            return;
//        }

        if (!isAllowedSymbol(symbol)) {
            return;
        }

        Instant now = Instant.now();
        LocalDateTime ldt = LocalDateTime.ofInstant(now, zone);
        long epochSecond = now.getEpochSecond();
        int secNow = ldt.getSecond();

        // 0) ALWAYS: update tape (even in cooldown). No spam logging here.
        maybeUpdateTape(epochSecond, secNow, analyze);

        // 1) ALWAYS: process exit-check at :14/:44 (even in cooldown).
        maybeExitCheckAndCooldown(symbol, ldt, epochSecond);

        // 2) Hard STOP (stops trades only; tape/checks already done above).
        if (isStopped()) {
            return;
        }

        // 3) Cooldown blocks ONLY trading. Tape/checks above still run.
        if (GLOBAL_COOLDOWN_ON.get() == 1) {
            return;
        }

        // 4) Trade only at :29/:59
        if (!isTradeSecond(secNow)) {
            return;
        }

        // 5) Anti-dup per second (global)
        if (!tryAcquireGlobalTradeSecond(epochSecond)) {
            return;
        }

        // 6) Release stale in-flight if needed (prevents permanent blocking).
        maybeReleaseStaleInFlight(epochSecond, ldt);

        // 7) Stake + contract
        StakeSnapshot stake = snapshotStake();
        Contract contract = new Contract(symbol, stake.stakePerSide(), 15, "s", "stake");

        // 8) Reserve global in-flight
        if (!tryReserveInFlight(epochSecond, symbol, stake.stakePerSide())) {
            log.accept("⏳ SKIP (in-flight) makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " time=" + ldt
                    + " epochSecond=" + epochSecond);
            return;
        }

        // 9) Start async trade + completion handler
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

    /**
     * Updates tape with the most recent level we have.
     * We prefer snap.last(), but we never assume "full" is required to have a valid last value.
     */
    private void maybeUpdateTape(long epochSecond, int secNow, AnalyzeContainer analyze) {
        if (analyze == null) {
            return;
        }
        AnalyzeContainer.LevelsEdgesSnapshot snap = analyze.snapshotLevelsEdges();
        if (snap == null) {
            return;
        }
        if (snap.size() <= 0) {
            return;
        }

        long last = snap.last();
        GLOBAL_TAPE.put(epochSecond, last);

        // Entry seconds are important checkpoints (no spam: log only at :00/:30).
        if (secNow == 0 || secNow == 30) {
            log.accept("🟦 LEVEL_ENTRY makerId=" + System.identityHashCode(this)
                    + " symbol=stpRNG time=" + LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zone)
                    + " entrySec=" + String.format("%02d", secNow)
                    + " level=" + last
                    + " tapeEpoch=" + epochSecond);
        }
    }

    /**
     * Exit-check:
     * - only at :14/:44
     * - entryEpoch = nowEpoch - 14 (00 or 30)
     * - exitEpoch = nowEpoch (14 or 44)
     * - WIN: exit != entry
     * - "good" WIN: abs(diff) > 1
     * - tie => LOSE
     *
     * Cooldown release:
     * - Need 6 consecutive WIN
     * - Within those 6, need at least 3 good WIN (abs(diff)>1)
     */
    private void maybeExitCheckAndCooldown(String symbol, LocalDateTime ldt, long nowEpochSecond) {
        int exitSec = ldt.getSecond();
        if (exitSec != EXIT_SEC_A && exitSec != EXIT_SEC_B) {
            return;
        }

        if (!tryAcquireExitCheckSecond(nowEpochSecond)) {
            return;
        }

        long entryEpochSecond = nowEpochSecond - ENTRY_TO_EXIT_SECONDS;

        Long entryLevel = GLOBAL_TAPE.get(entryEpochSecond);
        Long exitLevel = GLOBAL_TAPE.get(nowEpochSecond);

        int entrySec = (exitSec == EXIT_SEC_A) ? 0 : 30;
        int clickSec = clickSecondForEntrySecond(entrySec);

        if (entryLevel == null || exitLevel == null) {
            // Not counted => do not change release counters
            log.accept("⏳ LEVEL_EXIT makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " time=" + ldt
                    + " clickSec=" + String.format("%02d", clickSec)
                    + " entrySec=" + String.format("%02d", entrySec)
                    + " exitSec=" + String.format("%02d", exitSec)
                    + " entry=" + (entryLevel == null ? "?" : entryLevel)
                    + " exit=" + (exitLevel == null ? "?" : exitLevel)
                    + " reason=" + (entryLevel == null ? "NO_ENTRY_IN_TAPE" : "NO_EXIT_IN_TAPE")
                    + " cooldown=" + (GLOBAL_COOLDOWN_ON.get() == 1)
                    + " confirm=" + GLOBAL_RELEASE_CONFIRM_STREAK.get() + "/" + RELEASE_CONFIRMATIONS
                    + " good=" + GLOBAL_RELEASE_GOOD_COUNT.get() + "/" + RELEASE_GOOD_MIN
                    + " note=NOT_COUNTED");
            return;
        }

        long diff = exitLevel - entryLevel;
        boolean win = !Objects.equals(entryLevel, exitLevel); // tie => lose
        boolean good = win && Math.abs(diff) > GOOD_DIFF_ABS_GT;

        // Update 5-minute stats by click second (59/29/..): we count WIN only (tie=lose).
        GLOBAL_PROFIT_TRACKER.onObservation(nowEpochSecond, clickSec, win);
        String stats = GLOBAL_PROFIT_TRACKER.maybeBuildPeriodicLog(nowEpochSecond, ldt, symbol);
        if (stats != null) {
            log.accept(stats);
        }

        // Log result
        log.accept((win ? "✅" : "❌") + " LEVEL_EXIT makerId=" + System.identityHashCode(this)
                + " symbol=" + symbol
                + " time=" + ldt
                + " clickSec=" + String.format("%02d", clickSec)
                + " entrySec=" + String.format("%02d", entrySec)
                + " exitSec=" + String.format("%02d", exitSec)
                + " entry=" + entryLevel
                + " exit=" + exitLevel
                + " diff=" + diff
                + " result=" + (win ? (good ? "WIN_GOOD(abs(diff)>1)" : "WIN_WEAK(abs(diff)==1)") : "LOSE_TIE(exit==entry)")
                + " cooldown=" + (GLOBAL_COOLDOWN_ON.get() == 1)
                + " confirm=" + GLOBAL_RELEASE_CONFIRM_STREAK.get() + "/" + RELEASE_CONFIRMATIONS
                + " good=" + GLOBAL_RELEASE_GOOD_COUNT.get() + "/" + RELEASE_GOOD_MIN);

        if (isStopped()) {
            return;
        }

        // Release logic applies only when cooldown is ON
        if (GLOBAL_COOLDOWN_ON.get() != 1) {
            return;
        }

        if (win) {
            int confirm = GLOBAL_RELEASE_CONFIRM_STREAK.incrementAndGet();
            int goodCount = GLOBAL_RELEASE_GOOD_COUNT.get();
            if (good) {
                goodCount = GLOBAL_RELEASE_GOOD_COUNT.incrementAndGet();
            }

            log.accept("🛑 COOLDOWN_CHECK makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " time=" + ldt
                    + " confirm=" + confirm + "/" + RELEASE_CONFIRMATIONS
                    + " good=" + goodCount + "/" + RELEASE_GOOD_MIN
                    + " note=" + (good ? "COUNTED_POSITIVE_GOOD" : "COUNTED_POSITIVE_WEAK"));

            if (confirm >= RELEASE_CONFIRMATIONS && goodCount >= RELEASE_GOOD_MIN) {
                exitCooldown(ldt, symbol);
            }
        } else {
            GLOBAL_RELEASE_CONFIRM_STREAK.set(0);
            GLOBAL_RELEASE_GOOD_COUNT.set(0);

            log.accept("🛑 COOLDOWN_CHECK makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " time=" + ldt
                    + " confirm=0/" + RELEASE_CONFIRMATIONS
                    + " good=0/" + RELEASE_GOOD_MIN
                    + " note=COUNTED_NEGATIVE_RESET");
        }
    }

    private void enterCooldown(LocalDateTime ldt, String symbol, String reason) {
        if (isStopped()) {
            return;
        }

        if (GLOBAL_COOLDOWN_ON.compareAndSet(0, 1)) {
            GLOBAL_FAIL_STREAK.set(0);
            GLOBAL_RELEASE_CONFIRM_STREAK.set(0);
            GLOBAL_RELEASE_GOOD_COUNT.set(0);

            log.accept("🛑 COOLDOWN_START makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " time=" + ldt
                    + " reason=" + reason
                    + " releaseRule=6x WIN(exit!=entry) AND >=3x abs(diff)>1 via tape at :14/:44");
        }
    }

    private void exitCooldown(LocalDateTime ldt, String symbol) {
        if (isStopped()) {
            return;
        }

        GLOBAL_COOLDOWN_ON.set(0);
        GLOBAL_RELEASE_CONFIRM_STREAK.set(0);
        GLOBAL_RELEASE_GOOD_COUNT.set(0);

        log.accept("🟩 COOLDOWN_END makerId=" + System.identityHashCode(this)
                + " symbol=" + symbol
                + " time=" + ldt
                + " rule=6 confirmations + 3 good");
    }

    private void enterStop(LocalDateTime ldt, String symbol, String reason) {
        if (GLOBAL_STOP_ON.compareAndSet(0, 1)) {
            GLOBAL_STOP_REASON = reason;
            GLOBAL_STOP_AT = ldt;

            // Reset everything that might keep trading alive
            GLOBAL_COOLDOWN_ON.set(0);
            GLOBAL_FAIL_STREAK.set(0);
            GLOBAL_RELEASE_CONFIRM_STREAK.set(0);
            GLOBAL_RELEASE_GOOD_COUNT.set(0);

            log.accept("🟥 STOP_TRADING makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " time=" + ldt
                    + " reason=" + reason);
        }
    }

    private boolean isStopped() {
        return GLOBAL_STOP_ON.get() == 1;
    }

    private boolean isAllowedSymbol(String symbol) {
        return "stpRNG".equals(symbol);
    }

    private boolean isTradeSecond(int sec) {
        return sec == TRADE_SEC_A || sec == TRADE_SEC_B;
    }

    private StakeSnapshot snapshotStake() {
        synchronized (this) {
            int idx = ladderIdx;
            BigDecimal stakePerSide = LADDER[idx];
            return new StakeSnapshot(idx, stakePerSide);
        }
    }

    private void maybeReleaseStaleInFlight(long nowEpochSecond, LocalDateTime ldt) {
        InFlightTrade cur = GLOBAL_IN_FLIGHT.get(GLOBAL_IN_FLIGHT_KEY);
        if (cur == null) {
            return;
        }
        long age = nowEpochSecond - cur.epochSecond();
        if (age <= IN_FLIGHT_STALE_SECONDS) {
            return;
        }
        if (GLOBAL_IN_FLIGHT.remove(GLOBAL_IN_FLIGHT_KEY, cur)) {
            log.accept("⚠️ IN_FLIGHT_STALE_RELEASE makerId=" + System.identityHashCode(this)
                    + " symbol=" + cur.symbol()
                    + " time=" + ldt
                    + " ageSec=" + age
                    + " note=releasedToAvoidDeadlock");
        }
    }

    private boolean tryReserveInFlight(long epochSecond, String symbol, BigDecimal stakePerSide) {
        InFlightTrade placeholder = new InFlightTrade(epochSecond, symbol, stakePerSide, null);
        return GLOBAL_IN_FLIGHT.putIfAbsent(GLOBAL_IN_FLIGHT_KEY, placeholder) == null;
    }

    private void startTradeAndTrackResult(
            long epochSecond,
            String symbol,
            Contract contract,
            BigDecimal stakePerSide
    ) {
        CompletableFuture<DerivTradingService.BothBuyStatus> fut = trading.buyBothAndWaitAsync(contract);

        InFlightTrade running = new InFlightTrade(epochSecond, symbol, stakePerSide, fut);
        GLOBAL_IN_FLIGHT.put(GLOBAL_IN_FLIGHT_KEY, running);

        fut.whenComplete((status, ex) -> {
            try {
                applyResult(symbol, status, ex);
            } finally {
                GLOBAL_IN_FLIGHT.remove(GLOBAL_IN_FLIGHT_KEY, running);
            }
        });
    }

    private void applyResult(String symbol, DerivTradingService.BothBuyStatus status, Throwable ex) {
        if (isStopped()) {
            log.accept("🟥 RESULT (ignored, stopped) makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " status=" + ((ex != null) ? "ERROR" : (status != null && status.isSuccess() ? "SUCCESS" : "FAIL"))
                    + " err=" + (ex != null ? ex : "null"));
            return;
        }

        boolean success = (ex == null) && status != null && status.isSuccess();

        int prevIdx;
        int newIdx;
        boolean failOnLastStep;

        synchronized (this) {
            prevIdx = ladderIdx;
            failOnLastStep = (!success) && (prevIdx == LADDER.length - 1);

            if (success) {
                ladderIdx = 0;
            } else {
                ladderIdx = Math.min(ladderIdx + 1, LADDER.length - 1);
            }
            newIdx = ladderIdx;
        }

        if (failOnLastStep) {
            String reason = (ex != null ? "LAST_STEP_ERROR->FAIL" : "LAST_STEP_FAIL");
            enterStop(LocalDateTime.now(zone), symbol, reason);

            log.accept("❌ RESULT makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " status=" + (ex != null ? "ERROR->FAIL" : "FAIL")
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + LADDER[newIdx]
                    + " NOTE=STOPPED_AFTER_LAST_STEP"
                    + (ex != null ? " err=" + ex : ""));
            return;
        }

        if (success) {
            GLOBAL_FAIL_STREAK.set(0);
        } else {
            int fails = GLOBAL_FAIL_STREAK.incrementAndGet();
            if (fails >= FAIL_STREAK_LIMIT) {
                enterCooldown(LocalDateTime.now(zone), symbol, (ex != null ? "ERROR->FAIL" : "FAIL"));
            }
        }

        if (success) {
            log.accept("✅ RESULT makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " status=SUCCESS"
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + LADDER[newIdx]
                    + " failStreak=0");
        } else {
            int fails = GLOBAL_FAIL_STREAK.get();
            log.accept("❌ RESULT makerId=" + System.identityHashCode(this)
                    + " symbol=" + symbol
                    + " status=" + (ex != null ? "ERROR->FAIL" : "FAIL")
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + LADDER[newIdx]
                    + " failStreak=" + fails
                    + (ex != null ? " err=" + ex : ""));
        }
    }

    private record StakeSnapshot(int ladderIdxAtSend, BigDecimal stakePerSide) {}

    private record InFlightTrade(
            long epochSecond,
            String symbol,
            BigDecimal stakePerSide,
            CompletableFuture<DerivTradingService.BothBuyStatus> future
    ) {}

    /**
     * Global tape for lastLevel by epochSecond.
     * Keeps bounded history and purges old entries.
     */
    private static final class GlobalLevelTape {
        private final int keepSeconds;
        private final ConcurrentMap<Long, Long> byEpoch = new ConcurrentHashMap<>();
        private final AtomicLong lastPurgeEpoch = new AtomicLong(-1);

        private GlobalLevelTape(int keepSeconds) {
            this.keepSeconds = keepSeconds;
        }

        void put(long epochSecond, long lastLevel) {
            byEpoch.put(epochSecond, lastLevel);
            maybePurge(epochSecond);
        }

        Long get(long epochSecond) {
            return byEpoch.get(epochSecond);
        }

        private void maybePurge(long nowEpochSecond) {
            long prev = lastPurgeEpoch.get();
            if (prev == nowEpochSecond) {
                return;
            }
            if (!lastPurgeEpoch.compareAndSet(prev, nowEpochSecond)) {
                return;
            }

            long minEpochInclusive = nowEpochSecond - (keepSeconds - 1);
            for (Long key : byEpoch.keySet()) {
                if (key < minEpochInclusive) {
                    byEpoch.remove(key);
                }
            }
        }
    }

    /**
     * Sliding-window tracker of "best click seconds" for entry->exit rule win=(exit!=entry).
     * Window is time-based (last N seconds).
     */
    private static final class GlobalSecondProfitabilityTracker {

        private static final int SECONDS = 60;

        private final AtomicLong lastRecordedEpochSecond = new AtomicLong(-1);
        private final AtomicLong lastLoggedBucket = new AtomicLong(-1);

        private final long windowSeconds;

        private final int[] wins = new int[SECONDS];
        private final int[] samples = new int[SECONDS];

        private final ArrayDeque<Event> events = new ArrayDeque<>(600);

        private GlobalSecondProfitabilityTracker(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        void onObservation(long epochSecond, int clickSecondOfMinute, boolean win) {
            if (!tryAcquireRecordSecond(epochSecond)) {
                return;
            }

            synchronized (this) {
                purgeOld(epochSecond);

                events.addLast(new Event(epochSecond, clickSecondOfMinute, win));
                samples[clickSecondOfMinute]++;
                if (win) {
                    wins[clickSecondOfMinute]++;
                }

                purgeOld(epochSecond);
            }
        }

        String maybeBuildPeriodicLog(long epochSecond, LocalDateTime ldt, String symbol) {
            long bucket = epochSecond / windowSeconds;
            while (true) {
                long prev = lastLoggedBucket.get();
                if (prev == bucket) {
                    return null;
                }
                if (lastLoggedBucket.compareAndSet(prev, bucket)) {
                    break;
                }
            }

            int[] winsSnap;
            int[] samplesSnap;
            int totalSamples;
            synchronized (this) {
                purgeOld(epochSecond);
                winsSnap = Arrays.copyOf(wins, wins.length);
                samplesSnap = Arrays.copyOf(samples, samples.length);
                totalSamples = events.size();
            }

            final int minSamples = 3;

            int[] secs = new int[SECONDS];
            int secsCount = 0;
            for (int s = 0; s < SECONDS; s++) {
                if (samplesSnap[s] >= minSamples) {
                    secs[secsCount++] = s;
                }
            }

            Integer[] secsBoxed = new Integer[secsCount];
            for (int i = 0; i < secsCount; i++) {
                secsBoxed[i] = secs[i];
            }

            Arrays.sort(secsBoxed, Comparator
                    .<Integer>comparingDouble(s -> {
                        int smp = samplesSnap[s];
                        return smp == 0 ? 0.0 : ((double) winsSnap[s] / (double) smp);
                    }).reversed()
                    .thenComparingInt(s -> winsSnap[s]).reversed()
                    .thenComparingInt(s -> samplesSnap[s]).reversed()
                    .thenComparingInt(s -> s)
            );

            StringBuilder sb = new StringBuilder(512);
            sb.append("📊 5MIN_CLICK_SEC_STATS")
                    .append(" symbol=").append(symbol)
                    .append(" time=").append(ldt)
                    .append(" winRule=(exit!=entry) tie=LOSE")
                    .append(" windowSec=").append(windowSeconds)
                    .append(" totalSamples=").append(totalSamples)
                    .append(" note=attributedToClickSecond(entry-1)");

            sb.append("\n  PERFECT:");
            boolean anyPerfect = false;
            for (int s = 0; s < SECONDS; s++) {
                int smp = samplesSnap[s];
                if (smp >= minSamples && winsSnap[s] == smp) {
                    anyPerfect = true;
                    sb.append(' ').append(String.format("%02d", s))
                            .append('(').append(winsSnap[s]).append('/').append(smp).append(')');
                }
            }
            if (!anyPerfect) {
                sb.append(" none");
            }

            sb.append("\n  TOP:");
            int topN = Math.min(10, secsBoxed.length);
            if (topN == 0) {
                sb.append(" none (not enough data)");
            } else {
                for (int i = 0; i < topN; i++) {
                    int s = secsBoxed[i];
                    int w = winsSnap[s];
                    int smp = samplesSnap[s];
                    double wr = smp == 0 ? 0.0 : (100.0 * w / smp);
                    sb.append(' ').append(String.format("%02d", s))
                            .append('(').append(w).append('/').append(smp)
                            .append('=').append(String.format("%.0f", wr)).append("%)");
                }
            }

            return sb.toString();
        }

        private boolean tryAcquireRecordSecond(long epochSecond) {
            while (true) {
                long prev = lastRecordedEpochSecond.get();
                if (prev == epochSecond) {
                    return false;
                }
                if (lastRecordedEpochSecond.compareAndSet(prev, epochSecond)) {
                    return true;
                }
            }
        }

        private void purgeOld(long nowEpochSecond) {
            long minEpochInclusive = nowEpochSecond - (windowSeconds - 1);
            while (!events.isEmpty()) {
                Event e = events.peekFirst();
                if (e.epochSecond >= minEpochInclusive) {
                    break;
                }
                events.removeFirst();
                samples[e.clickSecondOfMinute]--;
                if (e.win) {
                    wins[e.clickSecondOfMinute]--;
                }
            }
        }

        private record Event(long epochSecond, int clickSecondOfMinute, boolean win) {}
    }
}
