// StreakFourFixedDurationTradeDecisionMaker.java
package com.zoosool.analyze;

import com.zoosool.deriv.BalanceHolder;
import com.zoosool.deriv.DerivTradingService;
import com.zoosool.enums.TradeMode;
import com.zoosool.model.AnalyzeContainer;
import com.zoosool.model.Contract;
import com.zoosool.model.MaPoint;
import com.zoosool.model.TickStatsSnapshot;
import com.zoosool.state.TradeWindowState;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class StreakFourFixedDurationTradeDecisionMaker implements TradeDecisionMaker {

    private static final BigDecimal[] LADDER = {
            BigDecimal.valueOf(1),
            BigDecimal.valueOf(2),
            BigDecimal.valueOf(5),
            BigDecimal.valueOf(15),
            BigDecimal.valueOf(40),
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(300),
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(1600),
            BigDecimal.valueOf(3500),
    };

    private static final int DIR_STREAK_REQUIRED = 4;
    private static final int CONTRACT_DURATION_SECONDS = 5;

    private static final int TAPE_KEEP_SECONDS = 600;
    private static final int MIN_HISTORY_SECONDS_BEFORE_TRADING = TAPE_KEEP_SECONDS;

    private static final String DEFAULT_DURATION_UNIT = "t";

    private enum Direction { UP, DOWN, NONE }

    private volatile boolean tradingEnabled = false;

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
    private final TradeWindowState tradeWindowState;

    // metronome: не даём слать больше одной ставки в секунду
    private long lastMetronomeEpochSecond = -1;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public StreakFourFixedDurationTradeDecisionMaker(
            DerivTradingService trading,
            BalanceHolder balanceHolder,
            Consumer<String> logger,
            TradeWindowState tradeWindowState) {
        this.trading = Objects.requireNonNull(trading, "trading");
        this.balanceHolder = Objects.requireNonNull(balanceHolder, "balanceHolder");
        this.log = Objects.requireNonNull(logger, "logger");
        this.recorder = new TradeHistoryRecorder(Path.of("trade-data"), s -> {});
        this.tradeWindowState = tradeWindowState;

        this.tradingEnabled = tradeWindowState.isAutoTradeEnabled();
        tradeWindowState.autoTradeEnabledProperty().addListener((obs, oldV, newV) -> {
            this.tradingEnabled = newV;
        });
    }

    // -------------------------------------------------------------------------
    // Main entry point — роутинг по режиму
    // -------------------------------------------------------------------------

    @Override
    public void decideAndTradeSnap(String symbol, TickStatsSnapshot snapshot) {
        if (!tradingEnabled) {          // было: !tradeWindowState.isAutoTradeEnabled()
            return;
        }
        if (snapshot == null) {
            return;
        }

        TradeMode mode = tradeWindowState.getTradeMode();
        if (mode == null) return;

        switch (mode) {
            case SNAP -> handleSnap(symbol, snapshot);
            case METRONOME -> fireMetronomeTick(symbol);
        }
    }

    // -------------------------------------------------------------------------
    // SNAP strategy (бывшая логика decideAndTradeSnap)
    // -------------------------------------------------------------------------

    private void handleSnap(String symbol, TickStatsSnapshot snapshot) {
        Integer xmaShort = snapshot.xmaShort();
        Map<Integer, MaPoint> mas = snapshot.movingAverages();
        MaPoint ma16 = (mas == null) ? null : mas.get(16);
        Integer maSide = (ma16 == null) ? null : ma16.side();

        if (xmaShort == null || maSide == null) return;
        if (xmaShort != 0) return;
        if (maSide == 0) return;

        DerivTradingService.Direction dir =
                (maSide > 0) ? DerivTradingService.Direction.DOWN
                        : DerivTradingService.Direction.UP;

        LocalDateTime ldt = LocalDateTime.now(zone);
        long nowEpochSecond = Instant.now().getEpochSecond();

        TradePlan plan;

        synchronized (this) {
            if (stopped || inFlight != null) {
                return;
            }

            StakeSnapshot stake = snapshotStakeLocked();

            Contract contract = new Contract(
                    symbol,
                    stake.stakePerSide(),
                    CONTRACT_DURATION_SECONDS,
                    DEFAULT_DURATION_UNIT,
                    tradeWindowState.getBasis(),
                    false
            );

            long tradeSeq = nextTradeSeq++;

            inFlight = new InFlightTrade(tradeSeq, nowEpochSecond, symbol, stake.stakePerSide(), null);

            plan = new TradePlan(
                    tradeSeq,
                    nowEpochSecond,
                    ldt,
                    symbol,
                    snapshot.lastQuote() == null ? 0L : Math.round(snapshot.lastQuote()),
                    contract,
                    stake,
                    (dir == DerivTradingService.Direction.UP) ? Direction.UP : Direction.DOWN
            );
        }

        log.accept("🟪 SNAP_TRADE"
                + " time=" + plan.ldt()
                + " tradeSeq=" + plan.tradeSeq()
                + " symbol=" + plan.symbol()
                + " maSide=" + maSide
                + " xmaShort=" + xmaShort
                + " dir=" + dir
                + " stakePerSide=" + plan.stake().stakePerSide()
                + " ladderIdx=" + plan.stake().ladderIdxAtSend()
                + " durationSec=" + CONTRACT_DURATION_SECONDS);

        CompletableFuture<DerivTradingService.BuySellResult> fut = trading.buyOneAndAwait(plan.contract(), dir);
        wireInFlightFuture(plan, fut);
    }

    // -------------------------------------------------------------------------
    // METRONOME strategy — одна ставка на каждый тик выбранного символа,
    // fire-and-forget: результат не ждём, in-flight/лестницу не трогаем.
    // -------------------------------------------------------------------------

    private void fireMetronomeTick(String symbol) {
        // символ должен совпадать с выбранным в окне
        var selected = tradeWindowState.getSelectedAsset();
        if (selected == null || !selected.symbol().equals(symbol)) {
            return;
        }

        // ранний выход по флагу
        if (!tradingEnabled) {
            return;
        }

        DerivTradingService.Direction dir = tradeWindowState.getDirection();
        if (dir == null) {
            return;
        }

        // stake из стейта, парсим на каждом тике; пусто/кривое -> пропуск
        BigDecimal stake = parseStakeQuiet(tradeWindowState.getStake());
        if (stake == null) {
            return;
        }

        Contract contract = new Contract(
                symbol,
                stake,
                1,                              // duration: 1 тик
                DEFAULT_DURATION_UNIT,          // "t"
                tradeWindowState.getBasis(),
                tradeWindowState.isAllowEquals()
        );

        // последняя проверка перед самой отправкой — вдруг выключили, пока считали
        if (!tradingEnabled) {
            return;
        }

        if (dir == DerivTradingService.Direction.UP) {
            trading.buyRise(contract);
        } else {
            trading.buyFall(contract);
        }
    }

    private static BigDecimal parseStakeQuiet(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return (v.signum() > 0) ? v : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // STREAK (disabled)
    // -------------------------------------------------------------------------

    @Override
    public void decideAndTrade(String symbol, AnalyzeContainer analyze) {
        if (true) {
            return;
        }
        if (!tradeWindowState.isAutoTradeEnabled()) {
            return;
        }

        if (!tradeWindowState.getSelectedAsset().symbol().equals(symbol)) {
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

            if (nowEpochSecond == lastProcessedEpochSecond) {
                return;
            }

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
                        tradeWindowState.getBasis(),
                        false
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
                tradeWindowState.getBasis(),
                plan.stake().stakePerSide().toPlainString(),
                plan.stake().ladderIdxAtSend(),
                prevIdx,
                newIdx,
                LADDER[newIdx].toPlainString(),
                success ? "SUCCESS" : "FAIL",
                (rootEx == null ? null : rootEx.toString()),
                -1L,
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