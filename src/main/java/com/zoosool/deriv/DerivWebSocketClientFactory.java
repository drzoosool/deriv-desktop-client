package com.zoosool.deriv;

import com.zoosool.config.DerivAppConfig;

import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

public class DerivWebSocketClientFactory {
    public static DerivWsClient getClient(DerivAppConfig cfg, Consumer<String> uiLog, TickHandler tickHandler) {
        Objects.requireNonNull(cfg, "cfg");
        if (cfg.derivToken() == null || cfg.derivToken().isBlank()) {
            throw new IllegalArgumentException("deriv.app.token is blank in config.deriv.properties");
        }
        if (cfg.derivAppId() <= 0) {
            throw new IllegalArgumentException("deriv.app.id must be > 0 in config.deriv.properties");
        }

        URI uri = URI.create("wss://ws.derivws.com/websockets/v3?app_id=" + cfg.derivAppId());
        uiLog.accept("Connecting to: " + uri);

        return new DerivWsClient(uri, cfg.derivToken(), uiLog, tickHandler);
    }
}
