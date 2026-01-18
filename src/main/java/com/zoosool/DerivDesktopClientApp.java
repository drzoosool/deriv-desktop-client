package com.zoosool;

import com.zoosool.analyze.*;
import com.zoosool.config.DerivAppConfig;
import com.zoosool.deriv.*;
import com.zoosool.model.DerivSession;
import com.zoosool.window.AppLogView;
import com.zoosool.window.DerivClientMainWindow;
import com.zoosool.window.TickStatsView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DerivDesktopClientApp extends Application {

    private DerivClientMainWindow derivClientMainWindow;
    private final AppLogView appLogView = new AppLogView();
    private final TickStatsView statsView = new TickStatsView(Platform::runLater);

    private ExecutorService appIoExecutor;
    private ScheduledExecutorService pingScheduler;

    private ExecutorService tickStatsExecutor;
    private TickEventRouterService tickEventRouterService;

    private DerivConnector connector;

    @Override
    public void init() {
        DerivAppConfig cfg = DerivAppConfig.load(Path.of("config.deriv.properties"));

        appIoExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-io");
            t.setDaemon(true);
            return t;
        });

        ConnectionStateController state = new DefaultConnectionStateController(
                ConnectionState.DISCONNECTED,
                Platform::runLater,
                appLogView.logger()
        );

        tickStatsExecutor = Executors.newFixedThreadPool(5, newDaemonThreadFactory("tick-stats-"));

        DerivCurrencyHolder derivCurrencyHolder = new DerivCurrencyHolder();
        DerivConnectorHolder derivConnectorHolder = new DerivConnectorHolder();
        BalanceHolder balanceHolder = new BalanceHolder();
        DerivTradingService trading = new DerivTradingService(derivConnectorHolder, derivCurrencyHolder);
        NoFilterTradeDecisionMaker noFilterTradeDecisionMaker = new NoFilterTradeDecisionMaker(trading, appLogView.logger());
        TickDecisionEngineSink tickDecisionEngineSink = new TickDecisionEngineSink(statsView, noFilterTradeDecisionMaker);
        TickStatsCalculatorFactory statsCalcFactory = symbol -> new DefaultTickStatsCalculator(symbol, tickDecisionEngineSink);

        tickEventRouterService = new TickEventRouterService(
                appLogView.logger(),
                tickStatsExecutor,
                statsCalcFactory,
                256
        );

        TickHandler tickHandler = new TickHandler(appLogView.logger(), tickEventRouterService);
        BalanceHandler balanceHandler = new BalanceHandler(balanceHolder, appLogView.logger());
        DerivTickSubscriptionsService derivTickSubscriptionsService = new DerivTickSubscriptionsService(appLogView.logger());
        DerivBalanceSubscriptionsService derivBalanceSubscriptionsService = new DerivBalanceSubscriptionsService(appLogView.logger());

        connector = new DefaultDerivConnector(
                cfg,
                appLogView.logger(),
                state,
                appIoExecutor,
                tickHandler,
                derivTickSubscriptionsService,
                derivBalanceSubscriptionsService,
                balanceHandler
        );


        DerivSession derivSession = connector.ping().join();
        derivCurrencyHolder.setCurrency(derivSession.currency());
        derivConnectorHolder.setConnector(connector);

        DerivController derivController = new DerivController(trading, appLogView.logger());
        derivClientMainWindow = new DerivClientMainWindow(derivController, derivSession, appLogView, statsView);

        pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-ping");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Deriv Desktop Client (MVP)");
        stage.setScene(new Scene(derivClientMainWindow.getVisualArea(), 520, 800));
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        stage.show();

        // Periodic ping to keep connection alive + trigger reconnects when disconnected.
        // Note: ping() is non-blocking; it returns a future.
        pingScheduler.scheduleAtFixedRate(
                () -> connector.ping().exceptionally(ex -> null),
                10, 20, TimeUnit.SECONDS
        );
    }

    @Override
    public void stop() {
        if (connector != null) {
            try {
                connector.close();
            } catch (Exception ignore) {
            }
        }

        if (tickEventRouterService != null) {
            // This also shuts down tickStatsExecutor (router owns the executor lifecycle here).
            tickEventRouterService.stopAllAndShutdown(TickEventRouterService.DurationLike.seconds(3));
            tickEventRouterService = null;
            tickStatsExecutor = null;
        } else if (tickStatsExecutor != null) {
            tickStatsExecutor.shutdownNow();
            tickStatsExecutor = null;
        }

        if (pingScheduler != null) {
            pingScheduler.shutdownNow();
            pingScheduler = null;
        }

        if (appIoExecutor != null) {
            appIoExecutor.shutdownNow();
            appIoExecutor = null;
        }
    }

    private static java.util.concurrent.ThreadFactory newDaemonThreadFactory(String prefix) {
        AtomicInteger idx = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
