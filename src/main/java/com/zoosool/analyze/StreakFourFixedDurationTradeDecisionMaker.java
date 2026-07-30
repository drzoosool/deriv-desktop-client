// StreakFourFixedDurationTradeDecisionMaker.java
package com.zoosool.analyze;

import com.zoosool.deriv.BalanceHolder;
import com.zoosool.deriv.DerivTradingService;
import com.zoosool.enums.TradeMode;
import com.zoosool.logger.SnapTradeLogger;
import com.zoosool.model.Contract;
import com.zoosool.model.MaPoint;
import com.zoosool.model.TickSample;
import com.zoosool.model.TickStatsSnapshot;
import com.zoosool.state.TradeWindowState;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    private static final int CONTRACT_DURATION_SECONDS = 5;
    private static final String DEFAULT_DURATION_UNIT = "t";

    private volatile boolean tradingEnabled = false;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final DerivTradingService trading;

    @SuppressWarnings("unused")
    private final BalanceHolder balanceHolder;

    private final Consumer<String> log;
    private final ZoneId zone = ZoneId.systemDefault();

    private final SnapTradeLogger snapLogger;
    private final TradeWindowState tradeWindowState;

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
            Consumer<String> logger,
            TradeWindowState tradeWindowState) {

        this.trading = Objects.requireNonNull(trading, "trading");
        this.balanceHolder = Objects.requireNonNull(balanceHolder, "balanceHolder");
        this.log = Objects.requireNonNull(logger, "logger");
        this.tradeWindowState = Objects.requireNonNull(tradeWindowState, "tradeWindowState");

        this.snapLogger = new SnapTradeLogger(Path.of("snap-trades"), logger);

        this.tradingEnabled = tradeWindowState.isAutoTradeEnabled();

        tradeWindowState.autoTradeEnabledProperty().addListener((obs, oldV, newV) -> {
            this.tradingEnabled = newV;

            if (newV) {
                TradeMode m = tradeWindowState.getTradeMode();
                var sel = tradeWindowState.getSelectedAsset();

                logger.accept("SnapTradeLogger DIAG: enable=true mode=" + m
                        + " selected=" + (sel == null ? "null" : sel.symbol()));

                if (m == TradeMode.SNAP) {
                    snapLogger.start(sel == null ? "unknown" : sel.symbol());
                } else {
                    logger.accept("SnapTradeLogger DIAG: start() ПРОПУЩЕН, режим не SNAP");
                }
            } else {
                snapLogger.stop();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Main entry point — роутинг по режиму
    // -------------------------------------------------------------------------

    @Override
    public void decideAndTradeSnap(
            String symbol,
            TickStatsSnapshot snapshot,
            Supplier<List<TickSample>> researchTicksSupplier) {

        if (!tradingEnabled) {
            return;
        }

        if (snapshot == null) {
            return;
        }

        TradeMode mode = tradeWindowState.getTradeMode();
        if (mode == null) return;

        switch (mode) {
            case SNAP -> handleSnap(symbol, snapshot, researchTicksSupplier);
            case METRONOME -> fireMetronomeTick(symbol);
        }
    }

    // -------------------------------------------------------------------------
    // SNAP strategy
    // -------------------------------------------------------------------------

    private void handleSnap(
            String symbol,
            TickStatsSnapshot snapshot,
            Supplier<List<TickSample>> researchTicksSupplier) {

        Integer xmaShort = snapshot.xmaShort();

        Map<Integer, MaPoint> mas = snapshot.movingAverages();
        MaPoint ma16 = (mas == null) ? null : mas.get(16);
        Integer maSide = (ma16 == null) ? null : ma16.side();

        if (xmaShort == null || maSide == null) return;
        if (xmaShort != 0) return;
        if (maSide == 0) return;

        DerivTradingService.Direction signalDirection =
                (maSide > 0)
                        ? DerivTradingService.Direction.DOWN
                        : DerivTradingService.Direction.UP;

        // Пока DefaultTickStatsCalculator не накопил полное research-окно,
        // snapshotResearchTicks() возвращает пустой список.
        //
        // То есть до прогрева SNAP вообще не торгует.
        List<TickSample> researchTicks = researchTicksSupplier.get();
        if (researchTicks.isEmpty()) return;

        Instant sentAt = Instant.now();
        LocalDateTime ldt = LocalDateTime.ofInstant(sentAt, zone);
        long nowEpochSecond = sentAt.getEpochSecond();

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

            inFlight = new InFlightTrade(
                    tradeSeq,
                    nowEpochSecond,
                    symbol,
                    stake.stakePerSide(),
                    null
            );

            // Сейчас tradeDirection совпадает с signalDirection.
            // Позже при INVERT signalDirection останется исходным,
            // а tradeDirection будет фактически отправленным направлением.
            DerivTradingService.Direction tradeDirection = signalDirection;

            plan = new TradePlan(
                    tradeSeq,
                    nowEpochSecond,
                    sentAt,
                    ldt,
                    symbol,
                    snapshot.lastQuote() == null ? 0L : Math.round(snapshot.lastQuote()),
                    contract,
                    stake,
                    signalDirection,
                    tradeDirection,
                    snapshot,
                    researchTicks
            );
        }

        log.accept("🟪 SNAP_TRADE"
                + " time=" + plan.ldt()
                + " tradeSeq=" + plan.tradeSeq()
                + " symbol=" + plan.symbol()
                + " maSide=" + maSide
                + " xmaShort=" + xmaShort
                + " dir=" + plan.tradeDirection()
                + " stakePerSide=" + plan.stake().stakePerSide()
                + " ladderIdx=" + plan.stake().ladderIdxAtSend()
                + " durationSec=" + CONTRACT_DURATION_SECONDS
                + " researchTicks=" + plan.researchTicks().size());

        CompletableFuture<DerivTradingService.BuySellResult> fut =
                trading.buyOneAndAwait(
                        plan.contract(),
                        plan.tradeDirection()
                );

        wireInFlightFuture(plan, fut);
    }

    // -------------------------------------------------------------------------
    // METRONOME strategy
    //
    // Направление — строго из кнопки (tradeWindowState.getDirection()).
    // Никакого MA и анализа движения цены: на каждый тик выбранного символа
    // шлём одну ставку в сторону кнопки. fire-and-forget: результат не ждём,
    // in-flight/лестницу не трогаем.
    // -------------------------------------------------------------------------

    private void fireMetronomeTick(String symbol) {
        // символ должен совпадать с выбранным в окне
        var selected = tradeWindowState.getSelectedAsset();
        if (selected == null || !selected.symbol().equals(symbol)) {
            return;
        }

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
    // Async wiring
    // -------------------------------------------------------------------------

    private void wireInFlightFuture(
            TradePlan plan,
            CompletableFuture<DerivTradingService.BuySellResult> fut) {

        synchronized (this) {
            InFlightTrade cur = inFlight;

            if (cur != null && cur.tradeSeq() == plan.tradeSeq()) {
                inFlight = new InFlightTrade(
                        cur.tradeSeq(),
                        cur.epochSecond(),
                        cur.symbol(),
                        cur.stakePerSide(),
                        fut
                );
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

    private void applyResult(
            TradePlan plan,
            DerivTradingService.BuySellResult res,
            Throwable ex) {

        Instant resultAt = Instant.now();
        LocalDateTime ldt = LocalDateTime.ofInstant(resultAt, zone);

        Throwable rootEx = (ex == null) ? null : unwrapCompletion(ex);
        String exText = (rootEx == null) ? "" : (" ex=" + rootEx);

        boolean success =
                rootEx == null
                        && res == DerivTradingService.BuySellResult.SUCCESS;

        int prevIdx;
        int newIdx;
        boolean failOnLastStep;

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

            failOnLastStep =
                    !success
                            && prevIdx == LADDER.length - 1;

            if (success) {
                ladderIdx = 0;
            } else {
                ladderIdx = Math.min(
                        ladderIdx + 1,
                        LADDER.length - 1
                );
            }

            newIdx = ladderIdx;

            if (failOnLastStep) {
                stopped = true;
                stopReason =
                        rootEx != null
                                ? "LAST_STEP_ERROR->FAIL"
                                : "LAST_STEP_FAIL";
                stopAt = ldt;
            }
        }

        // Единственный SNAP-лог.
        // Здесь лежит и старая история сделки, и snapshot, и researchTicks.
        snapLogger.log(new SnapTradeLogger.Entry(
                plan.tradeSeq(),
                ldt.toString(),
                plan.signalSnapshot().at().toString(),
                plan.sentAt().toString(),
                plan.symbol(),
                plan.signalDirection().name(),
                plan.tradeDirection().name(),
                plan.stake().stakePerSide(),
                success ? "SUCCESS" : "FAIL",
                prevIdx,
                rootEx == null ? null : rootEx.toString(),
                plan.signalSnapshot(),
                plan.researchTicks()
        ));

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

        while ((t instanceof CompletionException || t instanceof ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }

        return t;
    }

    private StakeSnapshot snapshotStakeLocked() {
        return new StakeSnapshot(
                ladderIdx,
                LADDER[ladderIdx]
        );
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    private record StakeSnapshot(
            int ladderIdxAtSend,
            BigDecimal stakePerSide
    ) {}

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
            Instant sentAt,
            LocalDateTime ldt,
            String symbol,
            long level,
            Contract contract,
            StakeSnapshot stake,
            DerivTradingService.Direction signalDirection,
            DerivTradingService.Direction tradeDirection,
            TickStatsSnapshot signalSnapshot,
            List<TickSample> researchTicks
    ) {
        int durationSeconds() {
            return CONTRACT_DURATION_SECONDS;
        }
    }
}
