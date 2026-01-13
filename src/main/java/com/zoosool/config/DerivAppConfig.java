package com.zoosool.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public record DerivAppConfig(
        String derivToken,
        int derivAppId,
        String pushoverUserKey,
        String pushoverAppToken
) {
    public static DerivAppConfig load(Path path) {
        Objects.requireNonNull(path, "path");

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read config file: " + path.toAbsolutePath(), e);
        }

        String derivToken = require(p, "deriv.app.token");
        int derivAppId = parseInt(require(p, "deriv.app.id"), "deriv.app.id");

        String pushoverUserKey = p.getProperty("pushover.user.key", "").trim();
        String pushoverAppToken = p.getProperty("pushover.app.token", "").trim();

        return new DerivAppConfig(derivToken, derivAppId, pushoverUserKey, pushoverAppToken);
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalStateException("Missing required property: " + key);
        }
        return v.trim();
    }

    private static int parseInt(String v, String key) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid integer for " + key + ": " + v, e);
        }
    }
}
