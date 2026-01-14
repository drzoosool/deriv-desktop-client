package com.zoosool;

import com.zoosool.config.DerivAppConfig;
import com.zoosool.deriv.*;
import com.zoosool.model.DerivSession;
import com.zoosool.window.AppLogView;
import com.zoosool.window.DerivClientMainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class DerivDesktopClientApp extends Application {

    private DerivClientMainWindow derivClientMainWindow;
    private final AppLogView appLogView = new AppLogView();

    private ExecutorService appIoExecutor;
    private ScheduledExecutorService pingScheduler;

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

        TickHandler tickHandler = new TickHandler(appLogView.logger());
        DerivTickSubscriptionsService derivTickSubscriptionsService = new DerivTickSubscriptionsService(appLogView.logger());
        connector = new DefaultDerivConnector(
                cfg,
                appLogView.logger(),
                state,
                appIoExecutor,
                tickHandler,
                derivTickSubscriptionsService);

        DerivSession derivSession = connector.ping().join();

        DerivTradingService trading = new DerivTradingService(connector, derivSession.currency());
        DerivController derivController = new DerivController(trading, appLogView.logger());

        derivClientMainWindow = new DerivClientMainWindow(derivController, derivSession, appLogView);

        pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-ping");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Deriv Desktop Client (MVP)");
        stage.setScene(new Scene(derivClientMainWindow.getVisualArea(), 520, 620));
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        stage.show();

        // Periodic ping to keep connection alive + trigger reconnects when disconnected.
        // Note: ping() is non-blocking; it returns a future.
        pingScheduler.scheduleAtFixedRate(
                () -> connector.ping().exceptionally(ex -> null),
                10, 20, java.util.concurrent.TimeUnit.SECONDS
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

        if (pingScheduler != null) {
            pingScheduler.shutdownNow();
            pingScheduler = null;
        }

        if (appIoExecutor != null) {
            appIoExecutor.shutdownNow();
            appIoExecutor = null;
        }
    }
}
