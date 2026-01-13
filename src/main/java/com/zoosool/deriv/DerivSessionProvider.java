package com.zoosool.deriv;

import com.zoosool.model.DerivSession;

import java.util.concurrent.CompletableFuture;

public interface DerivSessionProvider {
    CompletableFuture<DerivSession> ready();
    DerivWsClient ws();
    void close();
}
