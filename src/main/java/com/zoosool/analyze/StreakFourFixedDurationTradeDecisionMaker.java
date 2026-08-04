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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StreakFourFixedDurationTradeDecisionMaker implements TradeDecisionMaker {

    // -------------------------------------------------------------------------
    // Money management
    // -------------------------------------------------------------------------

    private static final BigDecimal[] LADDER_STAKES = {
            BigDecimal.ONE,
            BigDecimal.valueOf(2),
            BigDecimal.valueOf(4),
            BigDecimal.valueOf(8)
    };

    // -------------------------------------------------------------------------
    // Win rate regime
    // -------------------------------------------------------------------------

    private static final int INITIAL_MIN_TRADES = 50;
    private static final double INITIAL_START_WIN_RATE = 0.51;

    private static final int REAL_MIN_TRADES = 50;
    private static final double REAL_STOP_WIN_RATE = 0.48;

    private static final int RESUME_BLOCK_SIZE = 30;
    private static final double RESUME_WIN_RATE = 0.50;

    // -------------------------------------------------------------------------
    // Contract
    // -------------------------------------------------------------------------

    private static final int CONTRACT_DURATION_SECONDS = 5;
    private static final int VIRTUAL_DURATION_TICKS = 5;
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
    private final SnapTradeLogger virtualSnapLogger;

    private final TradeWindowState tradeWindowState;

    // -------------------------------------------------------------------------
    // Trading state
    // -------------------------------------------------------------------------

    private int ladderIdx = 0;

    private long nextTradeSeq = 1;
    private long lastSettledTradeSeq = 0;

    private InFlightTrade inFlight = null;

    // -------------------------------------------------------------------------
    // Virtual contracts
    //
    // Каждый принятый SNAP обязательно получает virtual contract.
    //
    // Пока virtual contract существует, следующий SNAP не принимается.
    //
    // Map используется намеренно, чтобы уже созданный virtual contract
    // невозможно было случайно перезаписать новым.
    // -------------------------------------------------------------------------

    private final Map<Long, VirtualTrade> virtualTrades = new HashMap<>();

    // Для сравнения REAL и VIRTUAL.
    //
    // Результаты могут прийти в любом порядке.
    // Удаляем их только после того, как получили обе стороны.
    private final Map<Long, Boolean> realResults = new HashMap<>();
    private final Map<Long, Boolean> virtualResults = new HashMap<>();

    // -------------------------------------------------------------------------
    // MA16-SKIP
    // -------------------------------------------------------------------------

    private final Map<String, Instant> blockedAfterFailBySymbol = new HashMap<>();

    // -------------------------------------------------------------------------
    // Win rate regime
    // -------------------------------------------------------------------------

    private boolean realRegimeEnabled = false;
    private boolean realRegimeEverStarted = false;

    // До первого REAL считаем cumulative WR от начала сессии.
    private int initialVirtualTrades = 0;
    private int initialVirtualSuccess = 0;

    // После включения REAL считаем новый WR только от точки включения.
    private int realRegimeTrades = 0;
    private int realRegimeSuccess = 0;

    // После остановки REAL проверяем независимые блоки по 30 virtual.
    private int resumeBlockTrades = 0;
    private int resumeBlockSuccess = 0;

    private final AtomicInteger maxStakeMetronomeCount = new AtomicInteger(3);

    // -------------------------------------------------------------------------
    // Stop state
    //
    // По длине лестницы больше не останавливаемся.
    // Поля оставляем под настоящий hard stop.
    // -------------------------------------------------------------------------

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
        this.virtualSnapLogger = new SnapTradeLogger(Path.of("snap-virtual-trades"), logger);

        this.tradingEnabled = tradeWindowState.isAutoTradeEnabled();

        tradeWindowState.autoTradeEnabledProperty().addListener((obs, oldV, newV) -> {
            this.tradingEnabled = newV;
            if (tradingEnabled) {
                this.maxStakeMetronomeCount.set(3);
            }

            if (newV) {
                TradeMode m = tradeWindowState.getTradeMode();
                var sel = tradeWindowState.getSelectedAsset();

                logger.accept("SnapTradeLogger DIAG: enable=true mode=" + m
                        + " selected=" + (sel == null ? "null" : sel.symbol()));

                if (m == TradeMode.SNAP) {
                    String symbol = sel == null ? "unknown" : sel.symbol();
                    snapLogger.start(symbol);
                    virtualSnapLogger.start(symbol);
                } else {
                    logger.accept("SnapTradeLogger DIAG: start() ПРОПУЩЕН, режим не SNAP");
                }
            } else {
                snapLogger.stop();
                // virtualSnapLogger специально НЕ останавливаем здесь.
                //
                // Уже открытая виртуальная сделка обязана получить
                // результат даже после выключения AutoTrade.
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

        if (snapshot == null) return;

        // ---------------------------------------------------------------------
        // ВАЖНО
        //
        // Virtual contracts обслуживаются ДО любых проверок tradingEnabled,
        // stopped, TradeMode и т.д.
        //
        // Если виртуальная сделка уже создана, её результат обязан быть
        // досчитан.
        // ---------------------------------------------------------------------

        processVirtualTrades(symbol, snapshot);

        if (!tradingEnabled) return;

        TradeMode mode = tradeWindowState.getTradeMode();
        if (mode == null) return;

        switch (mode) {
            case SNAP -> handleSnapManaged(symbol, snapshot, researchTicksSupplier);
            case METRONOME -> fireMetronomeTick(symbol);
        }
    }

    // -------------------------------------------------------------------------
    // SNAP strategy
    // -------------------------------------------------------------------------

    private void handleSnapManaged(
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
                (maSide > 0) ? DerivTradingService.Direction.DOWN
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

        TradePlan plan = null;

        boolean skip;
        boolean regimeWait;
        long tradeSeq;
        StakeSnapshot stake;

        synchronized (this) {

            // -------------------------------------------------------------
            // ОДИН ГЛОБАЛЬНЫЙ SLOT
            //
            // Кто первый пришёл, тот и играет.
            //
            // Slot занят пока существует:
            // - REAL inFlight
            // ИЛИ
            // - хоть один незавершённый virtual contract.
            //
            // Поэтому REAL может завершиться раньше virtual и наоборот —
            // новый сигнал всё равно не войдёт.
            // -------------------------------------------------------------

            if (isSnapBusyLocked()) return;
            if (stopped) return;

            skip = shouldSkipLocked(symbol, snapshot, researchTicks);
            regimeWait = !skip && !realRegimeEnabled;

            tradeSeq = nextTradeSeq++;

            // Для SKIP / WAIT фиксируем ту ставку, которая была бы сделана
            // обычной стратегией.
            //
            // На реальную лестницу она не влияет.
            stake = snapshotLadderStakeLocked();

            // -------------------------------------------------------------
            // EVERY ACCEPTED SNAP -> VIRTUAL
            //
            // Он создаётся ДО отправки REAL.
            // После этого его нельзя потерять.
            // -------------------------------------------------------------

            virtualTrades.put(tradeSeq, new VirtualTrade(
                    tradeSeq, symbol, signalDirection, sentAt, snapshot, researchTicks,
                    stake.stakePerSide(), skip, regimeWait, false, 0.0, 0));

            if (skip) {
                log.accept("🟨 SNAP_SKIP"
                        + " time=" + ldt
                        + " tradeSeq=" + tradeSeq
                        + " symbol=" + symbol
                        + " dir=" + signalDirection
                        + " virtualStake=" + stake.stakePerSide()
                        + " reason=WAIT_MA16_CROSS"
                        + " signalAt=" + snapshot.at());
                return;
            }

            if (regimeWait) {
                log.accept("🟨 SNAP_REGIME_WAIT"
                        + " time=" + ldt
                        + " tradeSeq=" + tradeSeq
                        + " symbol=" + symbol
                        + " dir=" + signalDirection
                        + " virtualStake=" + stake.stakePerSide()
                        + " signalAt=" + snapshot.at()
                        + " initial=" + initialVirtualSuccess + "/" + initialVirtualTrades
                        + " realRegime=" + realRegimeSuccess + "/" + realRegimeTrades
                        + " resumeBlock=" + resumeBlockSuccess + "/" + resumeBlockTrades);
                return;
            }

            Contract contract = new Contract(
                    symbol, stake.stakePerSide(), CONTRACT_DURATION_SECONDS,
                    DEFAULT_DURATION_UNIT, tradeWindowState.getBasis(), false);

            inFlight = new InFlightTrade(tradeSeq, nowEpochSecond, symbol, stake.stakePerSide(), null);

            DerivTradingService.Direction tradeDirection = signalDirection;

            plan = new TradePlan(
                    tradeSeq, nowEpochSecond, sentAt, ldt, symbol,
                    snapshot.lastQuote() == null ? 0L : Math.round(snapshot.lastQuote()),
                    contract, stake, signalDirection, tradeDirection,
                    snapshot, researchTicks);
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
                trading.buyOneAndAwait(plan.contract(), plan.tradeDirection());

        wireInFlightFuture(plan, fut);
    }

    // -------------------------------------------------------------------------
    // SNAP serialization
    // -------------------------------------------------------------------------

    private boolean isSnapBusyLocked() {
        return inFlight != null || !virtualTrades.isEmpty();
    }

    // -------------------------------------------------------------------------
    // MA16-SKIP
    // -------------------------------------------------------------------------

    private boolean shouldSkipLocked(
            String symbol,
            TickStatsSnapshot snapshot,
            List<TickSample> researchTicks) {

        Instant blockedAfter = blockedAfterFailBySymbol.get(symbol);
        if (blockedAfter == null) return false;

        boolean crossed = hasMa16CrossAfter(researchTicks, blockedAfter);
        if (!crossed) return true;

        blockedAfterFailBySymbol.remove(symbol);

        log.accept("🟩 SNAP_UNBLOCK"
                + " symbol=" + symbol
                + " blockedAfter=" + blockedAfter
                + " signalAt=" + snapshot.at());

        return false;
    }

    private boolean hasMa16CrossAfter(List<TickSample> ticks, Instant after) {
        if (ticks == null || ticks.size() < 17) return false;

        Integer previousSide = null;

        for (int i = 15; i < ticks.size(); i++) {
            TickSample current = ticks.get(i);

            double sum = 0.0;
            for (int j = i - 15; j <= i; j++) {
                sum += ticks.get(j).quote();
            }
            double ma = sum / 16.0;

            int side = Double.compare(current.quote() - ma, 0.0);
            if (side == 0) continue;

            if (previousSide != null && side != previousSide && current.at().isAfter(after)) {
                return true;
            }

            previousSide = side;
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Virtual contracts
    //
    // signal T0
    // next tick of SAME symbol = OPEN
    // then wait another 5 ticks of SAME symbol
    // tick #5 after OPEN = CLOSE
    // -------------------------------------------------------------------------

    private void processVirtualTrades(String symbol, TickStatsSnapshot snapshot) {
        Double quote = snapshot.lastQuote();
        if (quote == null) return;

        List<VirtualSettlement> settlements = new ArrayList<>();

        synchronized (this) {
            List<Long> ids = new ArrayList<>(virtualTrades.keySet());

            for (Long tradeSeq : ids) {
                VirtualTrade trade = virtualTrades.get(tradeSeq);
                if (trade == null) continue;
                if (!trade.symbol().equals(symbol)) continue;

                VirtualProgress progress = processVirtualTradeTickLocked(trade, quote, snapshot.at());

                if (progress.trade() != null) {
                    virtualTrades.put(tradeSeq, progress.trade());
                } else {
                    virtualTrades.remove(tradeSeq);
                }

                if (progress.settlement() != null) {
                    settlements.add(progress.settlement());
                }
            }
        }

        for (VirtualSettlement settlement : settlements) {
            settleVirtualTrade(settlement);
        }
    }

    private VirtualProgress processVirtualTradeTickLocked(
            VirtualTrade trade, double quote, Instant tickAt) {

        // -------------------------------------------------------------
        // Первый тик ПОСЛЕ signal = OPEN.
        //
        // Сам signal tick сюда попасть не может:
        // processVirtualTrades() вызывается до создания VirtualTrade.
        // -------------------------------------------------------------

        if (!trade.opened()) {
            VirtualTrade opened = new VirtualTrade(
                    trade.tradeSeq(), trade.symbol(), trade.direction(), trade.signalAt(),
                    trade.signalSnapshot(), trade.researchTicks(), trade.stake(),
                    trade.skip(), trade.regimeWait(), true, quote, 0);

            log.accept("🟦 VIRTUAL_OPEN"
                    + " tradeSeq=" + trade.tradeSeq()
                    + " symbol=" + trade.symbol()
                    + " skip=" + trade.skip()
                    + " regimeWait=" + trade.regimeWait()
                    + " dir=" + trade.direction()
                    + " signalAt=" + trade.signalAt()
                    + " openAt=" + tickAt
                    + " openQuote=" + quote);

            return new VirtualProgress(opened, null);
        }

        int ticksAfterOpen = trade.ticksAfterOpen() + 1;

        if (ticksAfterOpen < VIRTUAL_DURATION_TICKS) {
            VirtualTrade updated = new VirtualTrade(
                    trade.tradeSeq(), trade.symbol(), trade.direction(), trade.signalAt(),
                    trade.signalSnapshot(), trade.researchTicks(), trade.stake(),
                    trade.skip(), trade.regimeWait(), true, trade.openQuote(), ticksAfterOpen);

            return new VirtualProgress(updated, null);
        }

        boolean success = isVirtualSuccess(trade.direction(), trade.openQuote(), quote);

        VirtualSettlement settlement = new VirtualSettlement(trade, tickAt, quote, success);

        return new VirtualProgress(null, settlement);
    }

    private boolean isVirtualSuccess(
            DerivTradingService.Direction direction, double openQuote, double closeQuote) {

        if (direction == DerivTradingService.Direction.UP) {
            return closeQuote > openQuote;
        }
        return closeQuote < openQuote;
    }

    private void settleVirtualTrade(VirtualSettlement settlement) {
        VirtualTrade trade = settlement.trade();
        boolean success = settlement.success();

        LocalDateTime ldt = LocalDateTime.ofInstant(settlement.closeAt(), zone);

        synchronized (this) {
            virtualResults.put(trade.tradeSeq(), success);

            if (!trade.skip()) {
                if (!success) {
                    blockedAfterFailBySymbol.put(trade.symbol(), trade.signalSnapshot().at());
                }

                registerRegimeResultLocked(success);
            }
        }

        // ---------------------------------------------------------------------
        // Отдельный полный virtual log.
        //
        // SKIP / WAIT здесь выглядят как обычные virtual сделки,
        // но error содержит причину, почему REAL не отправлялся.
        // ---------------------------------------------------------------------

        String virtualReason = null;

        if (trade.skip()) {
            virtualReason = "VIRTUAL_SKIP";
        } else if (trade.regimeWait()) {
            virtualReason = "VIRTUAL_REGIME_WAIT";
        }

        virtualSnapLogger.log(new SnapTradeLogger.Entry(
                trade.tradeSeq(),
                ldt.toString(),
                trade.signalSnapshot().at().toString(),
                trade.signalAt().toString(),
                trade.symbol(),
                trade.direction().name(),
                trade.direction().name(),
                trade.stake(),
                success ? "SUCCESS" : "FAIL",
                0,
                virtualReason == null ? "VIRTUAL_REAL_MIRROR" : virtualReason,
                trade.signalSnapshot(),
                trade.researchTicks()));

        log.accept("🟦 VIRTUAL_RESULT"
                + " time=" + ldt
                + " tradeSeq=" + trade.tradeSeq()
                + " symbol=" + trade.symbol()
                + " skip=" + trade.skip()
                + " regimeWait=" + trade.regimeWait()
                + " dir=" + trade.direction()
                + " openQuote=" + trade.openQuote()
                + " closeQuote=" + settlement.closeQuote()
                + " result=" + (success ? "SUCCESS" : "FAIL")
                + " realRegimeEnabled=" + realRegimeEnabled
                + " initial=" + initialVirtualSuccess + "/" + initialVirtualTrades
                + " realRegime=" + realRegimeSuccess + "/" + realRegimeTrades
                + " resumeBlock=" + resumeBlockSuccess + "/" + resumeBlockTrades);

        if (!trade.skip() && !trade.regimeWait()) {
            logRealVirtualComparison(trade.tradeSeq());
        }

        cleanupResultState(trade.tradeSeq(), trade.skip() || trade.regimeWait());
    }

    // -------------------------------------------------------------------------
    // Win rate regime
    // -------------------------------------------------------------------------

    private void registerRegimeResultLocked(boolean success) {
        if (!realRegimeEverStarted) {
            initialVirtualTrades++;
            if (success) initialVirtualSuccess++;

            if (initialVirtualTrades >= INITIAL_MIN_TRADES
                    && winRate(initialVirtualSuccess, initialVirtualTrades) >= INITIAL_START_WIN_RATE) {

                realRegimeEnabled = true;
                realRegimeEverStarted = true;

                realRegimeTrades = 0;
                realRegimeSuccess = 0;

                log.accept("🟩 SNAP_REGIME_ON"
                        + " reason=INITIAL_WR"
                        + " success=" + initialVirtualSuccess
                        + " total=" + initialVirtualTrades
                        + " winRate=" + winRate(initialVirtualSuccess, initialVirtualTrades));
            }

            return;
        }

        if (realRegimeEnabled) {
            realRegimeTrades++;
            if (success) realRegimeSuccess++;

            if (realRegimeTrades >= REAL_MIN_TRADES
                    && winRate(realRegimeSuccess, realRegimeTrades) < REAL_STOP_WIN_RATE) {

                realRegimeEnabled = false;

                resumeBlockTrades = 0;
                resumeBlockSuccess = 0;

                log.accept("🟥 SNAP_REGIME_OFF"
                        + " reason=REAL_WR"
                        + " success=" + realRegimeSuccess
                        + " total=" + realRegimeTrades
                        + " winRate=" + winRate(realRegimeSuccess, realRegimeTrades));
            }

            return;
        }

        resumeBlockTrades++;
        if (success) resumeBlockSuccess++;

        if (resumeBlockTrades < RESUME_BLOCK_SIZE) return;

        double wr = winRate(resumeBlockSuccess, resumeBlockTrades);

        if (wr >= RESUME_WIN_RATE) {
            realRegimeEnabled = true;

            realRegimeTrades = 0;
            realRegimeSuccess = 0;

            log.accept("🟩 SNAP_REGIME_ON"
                    + " reason=RESUME_BLOCK"
                    + " success=" + resumeBlockSuccess
                    + " total=" + resumeBlockTrades
                    + " winRate=" + wr);
        } else {
            log.accept("🟨 SNAP_REGIME_WAIT_BLOCK"
                    + " success=" + resumeBlockSuccess
                    + " total=" + resumeBlockTrades
                    + " winRate=" + wr);
        }

        resumeBlockTrades = 0;
        resumeBlockSuccess = 0;
    }

    private static double winRate(int success, int total) {
        if (total <= 0) return 0.0;
        return (double) success / total;
    }

    // -------------------------------------------------------------------------
    // Fixed ladder
    //
    // 1F -> 2F -> 4F -> 8F -> STOP -> 1
    //
    // Любой SUCCESS -> 1.
    //
    // После FAIL на 8 старый убыток фиксируется,
    // следующая лестница начинается заново с 1.
    // -------------------------------------------------------------------------

    private StakeSnapshot snapshotLadderStakeLocked() {
        return new StakeSnapshot(ladderIdx, LADDER_STAKES[ladderIdx]);
    }

    private BigDecimal nextStakeForLog() {
        synchronized (this) {
            return snapshotLadderStakeLocked().stakePerSide();
        }
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
        var selected = tradeWindowState.getSelectedAsset();
        if (selected == null || !selected.symbol().equals(symbol)) return;

        if (!tradingEnabled) return;

        DerivTradingService.Direction dir = tradeWindowState.getDirection();
        if (dir == null) return;

        BigDecimal stake = parseStakeQuiet(tradeWindowState.getStake());
        if (stake == null) return;

        Contract contract = new Contract(
                symbol, stake, 1, DEFAULT_DURATION_UNIT,
                tradeWindowState.getBasis(), tradeWindowState.isAllowEquals());

        if (!tradingEnabled) return;

        if (maxStakeMetronomeCount.get() > 0) {
            if (dir == DerivTradingService.Direction.UP) {
                trading.buyRise(contract);
            } else {
                trading.buyFall(contract);
            }
            maxStakeMetronomeCount.addAndGet(- 1);
        }
    }

    private static BigDecimal parseStakeQuiet(String raw) {
        if (raw == null || raw.isBlank()) return null;

        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() > 0 ? v : null;
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
                        cur.tradeSeq(), cur.epochSecond(), cur.symbol(), cur.stakePerSide(), fut);
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

    // -------------------------------------------------------------------------
    // REAL result
    // -------------------------------------------------------------------------

    private void applyResult(
            TradePlan plan,
            DerivTradingService.BuySellResult res,
            Throwable ex) {

        Instant resultAt = Instant.now();
        LocalDateTime ldt = LocalDateTime.ofInstant(resultAt, zone);

        Throwable rootEx = (ex == null) ? null : unwrapCompletion(ex);
        String exText = (rootEx == null) ? "" : (" ex=" + rootEx);

        boolean success = rootEx == null && res == DerivTradingService.BuySellResult.SUCCESS;

        int prevIdx;
        int newIdx;

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

            // Даже stopped не должен заставить забыть результат уже
            // существующей сделки.
            //
            // stopped запрещает НОВЫЕ сделки.
            // Уже отправленный REAL обязательно обрабатываем.

            prevIdx = ladderIdx;

            if (success) {
                ladderIdx = 0;
            } else if (ladderIdx >= LADDER_STAKES.length - 1) {
                ladderIdx = 0;
            } else {
                ladderIdx++;
            }

            newIdx = ladderIdx;

            realResults.put(plan.tradeSeq(), success);
        }

        // ---------------------------------------------------------------------
        // REAL log
        // ---------------------------------------------------------------------

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
                plan.researchTicks()));

        if (success) {
            log.accept("✅ RESULT"
                    + " time=" + ldt
                    + " tradeSeq=" + plan.tradeSeq()
                    + " symbol=" + plan.symbol()
                    + " res=SUCCESS"
                    + exText
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + nextStakeForLog());
        } else {
            log.accept("❌ RESULT"
                    + " time=" + ldt
                    + " tradeSeq=" + plan.tradeSeq()
                    + " symbol=" + plan.symbol()
                    + " res=FAIL"
                    + exText
                    + " ladder " + prevIdx + "->" + newIdx
                    + " nextStake=" + nextStakeForLog());
        }

        logRealVirtualComparison(plan.tradeSeq());
    }

    // -------------------------------------------------------------------------
    // REAL vs VIRTUAL
    // -------------------------------------------------------------------------

    private void logRealVirtualComparison(long tradeSeq) {
        Boolean real;
        Boolean virtual;

        synchronized (this) {
            real = realResults.get(tradeSeq);
            virtual = virtualResults.get(tradeSeq);

            if (real == null || virtual == null) return;

            realResults.remove(tradeSeq);
            virtualResults.remove(tradeSeq);
        }

        log.accept("🟦 REAL_VIRTUAL_COMPARE"
                + " tradeSeq=" + tradeSeq
                + " real=" + (real ? "SUCCESS" : "FAIL")
                + " virtual=" + (virtual ? "SUCCESS" : "FAIL")
                + " same=" + Objects.equals(real, virtual));
    }

    private void cleanupResultState(long tradeSeq, boolean virtualOnly) {
        if (!virtualOnly) return;

        synchronized (this) {
            // У SKIP / WAIT никогда не будет realResult,
            // поэтому virtual result после логирования можно удалить.
            virtualResults.remove(tradeSeq);
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

    private record VirtualTrade(
            long tradeSeq,
            String symbol,
            DerivTradingService.Direction direction,
            Instant signalAt,
            TickStatsSnapshot signalSnapshot,
            List<TickSample> researchTicks,
            BigDecimal stake,
            boolean skip,
            boolean regimeWait,
            boolean opened,
            double openQuote,
            int ticksAfterOpen
    ) {}

    private record VirtualSettlement(
            VirtualTrade trade,
            Instant closeAt,
            double closeQuote,
            boolean success
    ) {}

    private record VirtualProgress(
            VirtualTrade trade,
            VirtualSettlement settlement
    ) {}
}
