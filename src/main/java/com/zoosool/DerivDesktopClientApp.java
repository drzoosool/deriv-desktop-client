package com.zoosool;

import com.zoosool.config.DerivAppConfig;
import com.zoosool.deriv.*;
import com.zoosool.model.DerivSession;
import com.zoosool.window.AppLogView;
import com.zoosool.window.DerivClientMainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;

public class DerivDesktopClientApp extends Application {

    private DerivClientMainWindow derivClientMainWindow;
    private final AppLogView appLogView = new AppLogView();

    @Override
    public void init() {
        DerivAppConfig cfg = DerivAppConfig.load(Path.of("config.deriv.properties"));

        DerivWsClient ws = DerivWebSocketClientFactory.getClient(cfg, appLogView.logger());
        DefaultDerivSessionProvider defaultDerivSessionProvider = new DefaultDerivSessionProvider(ws);
        defaultDerivSessionProvider.connect();
        DerivSession derivSession = defaultDerivSessionProvider.ready().join();

        DerivController derivController = new DerivController(new DerivTradingService(defaultDerivSessionProvider), appLogView.logger());
        derivClientMainWindow = new DerivClientMainWindow(derivController, derivSession, appLogView);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Deriv Desktop Client (MVP)");
        stage.setScene(new Scene(derivClientMainWindow.getVisualArea(), 520, 620));
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        stage.show();
    }
}