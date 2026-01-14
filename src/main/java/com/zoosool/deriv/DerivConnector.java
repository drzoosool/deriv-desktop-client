package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoosool.model.DerivSession;

import java.util.concurrent.CompletableFuture;

public interface DerivConnector extends AutoCloseable {

    ConnectionState state();

    /**
     * The ONLY place where the connection is created/recreated.
     * Behavior:
     * - DISCONNECTED: starts connecting (CONNECTING), creates a new WS, authorizes,
     *   loads active_symbols and completes with a fresh DerivSession.
     * - CONNECTING: returns the in-flight future (does not start another connect).
     * - CONNECTED: sends a simple ping (fire-and-forget) and completes with the current session.
     * - CLOSED: fails.
     */
    CompletableFuture<DerivSession> ping();

    /**
     * Trading/critical entry point.
     * Never reconnects.
     * - If not CONNECTED -> fails fast.
     * - If the request fails -> marks DISCONNECTED (unless CONNECTING/CLOSED) and propagates the error.
     */
    CompletableFuture<JsonNode> sendRequest(ObjectNode req);

    /**
     * Trading can call this on failures.
     * Does NOT reconnect. Only marks DISCONNECTED if we were CONNECTED.
     */
    void invalidate(Throwable cause, String where);

    @Override
    void close();
}

