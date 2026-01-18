package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoosool.analyze.TickHandler;
import com.zoosool.config.DerivAppConfig;
import com.zoosool.model.ActiveSymbol;
import com.zoosool.model.DerivSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class DefaultDerivConnector implements DerivConnector {

    private final DerivAppConfig cfg;
    private final Consumer<String> log;
    private final ConnectionStateController state;
    private final Executor connectExecutor;
    private final DerivTickSubscriptionsService tickSubscriptions;
    private final DerivBalanceSubscriptionsService derivBalanceSubscriptionsService;
    private final TickHandler tickHandler;
    private final BalanceHandler balanceHandler;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Object swapLock = new Object();

    private volatile DerivWsClient ws;
    private volatile DerivSession session;

    private volatile CompletableFuture<DerivSession> connectingFuture;

    public DefaultDerivConnector(
            DerivAppConfig cfg,
            Consumer<String> log,
            ConnectionStateController state,
            Executor connectExecutor,
            TickHandler tickHandler,
            DerivTickSubscriptionsService tickSubscriptions,
            DerivBalanceSubscriptionsService derivBalanceSubscriptionsService,
            BalanceHandler balanceHandler
    ) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.log = Objects.requireNonNull(log, "log");
        this.state = Objects.requireNonNull(state, "state");
        this.connectExecutor = Objects.requireNonNull(connectExecutor, "connectExecutor");
        this.tickHandler = Objects.requireNonNull(tickHandler, "tickHandler");
        this.tickSubscriptions = Objects.requireNonNull(tickSubscriptions, "tickSubscriptions");
        this.balanceHandler = Objects.requireNonNull(balanceHandler, "balanceHandler");
        this.derivBalanceSubscriptionsService = derivBalanceSubscriptionsService;
    }

    @Override
    public ConnectionState state() {
        return state.get();
    }

    @Override
    public CompletableFuture<DerivSession> ping() {
        ConnectionState st = state.get();

        if (st == ConnectionState.CLOSED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Connector is CLOSED"));
        }

        if (st == ConnectionState.CONNECTING) {
            CompletableFuture<DerivSession> f = connectingFuture;
            return (f != null)
                    ? f
                    : CompletableFuture.failedFuture(new IllegalStateException("CONNECTING without future"));
        }

        if (st == ConnectionState.CONNECTED) {
            try {
                DerivWsClient cur = this.ws;
                if (cur == null || !cur.isOpen()) {
                    invalidate(new IllegalStateException("WS is not open"), "ping/open-check");
                    return CompletableFuture.failedFuture(new IllegalStateException("WS is not open"));
                }
                ObjectNode ping = mapper.createObjectNode();
                ping.put("ping", 1);
                cur.send(ping.toString());
            } catch (Exception ex) {
                invalidate(ex, "ping/send");
                return CompletableFuture.failedFuture(ex);
            }

            DerivSession s = this.session;
            if (s == null) {
                invalidate(new IllegalStateException("Session is null in CONNECTED"), "ping/session-null");
                return CompletableFuture.failedFuture(new IllegalStateException("Session is not ready"));
            }
            return CompletableFuture.completedFuture(s);
        }

        if (!state.compareAndSet(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING, "ping/start-connect")) {
            return ping();
        }

        CompletableFuture<DerivSession> f = new CompletableFuture<>();
        connectingFuture = f;

        CompletableFuture.runAsync(() -> doConnect(f), connectExecutor);

        return f;
    }

    private void doConnect(CompletableFuture<DerivSession> f) {
        final DerivWsClient wsLocal;

        try {
            if (state.get() == ConnectionState.CLOSED) {
                f.completeExceptionally(new IllegalStateException("Connector is CLOSED"));
                return;
            }
            wsLocal = DerivWebSocketClientFactory.getClient(cfg, log, tickHandler, balanceHandler);
        } catch (Throwable ex) {
            connectingFuture = null;
            if (state.get() != ConnectionState.CLOSED) {
                state.set(ConnectionState.DISCONNECTED, "connect/fail");
            }
            f.completeExceptionally(ex);
            return;
        }

        try {
            wsLocal.setDisconnectListener((where, ex) -> invalidate(ex, "ws/" + where));
            wsLocal.connect();

            DerivSession newSession = wsLocal.authorized()
                    .thenApply(authResp -> {
                        String currency = authResp.path("authorize").path("currency").asText(null);
                        if (currency == null || currency.isBlank()) {
                            throw new IllegalStateException("Cannot detect account currency from authorize response");
                        }
                        return currency;
                    })
                    .thenCompose(currency ->
                            loadActiveSymbols(wsLocal).thenApply(list -> new DerivSession(currency, list))
                    )
                    .join();

            tickHandler.onReconnect("connect/new-session");

            tickSubscriptions.subscribeAll(wsLocal, newSession.stepIndices()).join();
            derivBalanceSubscriptionsService.subscribe(wsLocal).join();

            DerivWsClient oldWs;
            synchronized (swapLock) {
                oldWs = this.ws;
                this.ws = wsLocal;
                this.session = newSession;
            }

            if (oldWs != null) {
                try { oldWs.close(); } catch (Exception ignore) {}
            }

            connectingFuture = null;
            state.set(ConnectionState.CONNECTED, "connect/success");
            f.complete(newSession);

        } catch (Throwable ex) {
            try { wsLocal.close(); } catch (Exception ignore) {}

            connectingFuture = null;
            if (state.get() != ConnectionState.CLOSED) {
                state.set(ConnectionState.DISCONNECTED, "connect/fail");
            }
            f.completeExceptionally(ex);
        }
    }

    private CompletableFuture<List<ActiveSymbol>> loadActiveSymbols(DerivWsClient ws) {
        ObjectNode req = mapper.createObjectNode();
        req.put("active_symbols", "brief");
        req.put("product_type", "basic");

        return ws.sendRequest(req).thenApply(resp -> {
            JsonNode list = resp.path("active_symbols");
            if (!list.isArray()) {
                throw new IllegalStateException("active_symbols not array: " + resp);
            }

            List<ActiveSymbol> out = new ArrayList<>();
            for (JsonNode s : list) {
                String symbol = s.path("symbol").asText(null);
                if (symbol == null || symbol.isBlank()) continue;
                String display = s.path("display_name").asText("");
                out.add(new ActiveSymbol(symbol, display));
            }

            if (out.isEmpty()) {
                throw new IllegalStateException("active_symbols is empty");
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(ObjectNode req) {
        Objects.requireNonNull(req, "req");

        ConnectionState st = state.get();
        if (st != ConnectionState.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected: " + st));
        }

        DerivWsClient cur = this.ws;
        if (cur == null || !cur.isOpen()) {
            invalidate(new IllegalStateException("WS is not open"), "sendRequest/open-check");
            return CompletableFuture.failedFuture(new IllegalStateException("WS is not open"));
        }

        CompletableFuture<JsonNode> f = cur.sendRequest(req);
        return f.whenComplete((resp, ex) -> {
            if (ex != null) {
                invalidate(ex, "sendRequest");
            }
        });
    }

    @Override
    public void invalidate(Throwable cause, String where) {
        ConnectionState st = state.get();

        if (st == ConnectionState.CLOSED || st == ConnectionState.CONNECTING) return;
        if (st == ConnectionState.DISCONNECTED) return;

        boolean changed = state.compareAndSet(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED, "invalidate/" + where);
        if (!changed) return;

        try {
            tickHandler.onReconnect("invalidate/" + where);
        } catch (Exception ignore) {
            // Do not let stats pipeline affect reconnect logic.
        }

        DerivWsClient cur = this.ws;
        if (cur != null) {
            try { cur.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void close() {
        if (state.get() == ConnectionState.CLOSED) return;

        state.set(ConnectionState.CLOSED, "close");

        CompletableFuture<DerivSession> cf = connectingFuture;
        if (cf != null) {
            cf.completeExceptionally(new CancellationException("Connector closed"));
        }
        connectingFuture = null;

        try {
            tickHandler.onReconnect("close");
        } catch (Exception ignore) {
            // ignore
        }

        DerivWsClient cur = this.ws;
        if (cur != null) {
            try { cur.close(); } catch (Exception ignore) {}
        }

        session = null;
        ws = null;
    }
}
