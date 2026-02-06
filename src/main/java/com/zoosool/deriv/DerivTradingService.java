// DerivTradingService.java
// (оставляю твою текущую логику: не ребаим, а ретраим polling до результата; лог только финала + ошибок polling)
package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoosool.model.Contract;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class DerivTradingService {

    private static final long AWAIT_POLL_PERIOD_MILLIS = 1_000;

    private static final long AWAIT_TIMEOUT_MILLIS = 10 * 60_000L;

    private static final long AWAIT_RETRY_DELAY_MILLIS = 1_000;

    private final ObjectMapper mapper = new ObjectMapper();

    private final DerivConnectorHolder connectorHolder;
    private final DerivCurrencyHolder derivCurrencyHolder;
    private final Consumer<String> log;

    private final ScheduledExecutorService poller;
    private final AtomicInteger reqSeq = new AtomicInteger(1);

    public DerivTradingService(
            DerivConnectorHolder connectorHolder,
            DerivCurrencyHolder derivCurrencyHolder,
            Consumer<String> logger
    ) {
        this.connectorHolder = Objects.requireNonNull(connectorHolder, "connectorHolder");
        this.derivCurrencyHolder = Objects.requireNonNull(derivCurrencyHolder, "derivCurrencyHolder");
        this.log = Objects.requireNonNull(logger, "logger");

        this.poller = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "deriv-contract-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<Void> buyBoth(Contract contract) {
        return buyBoth(contract, false);
    }

    public CompletableFuture<Void> buyBoth(Contract contract, boolean hold) {
        Objects.requireNonNull(contract, "contract");

        CompletableFuture<Long> up = buyRise(contract);
        CompletableFuture<Long> down = buyFall(contract);

        return up.thenCombine(down, (a, b) -> null);
    }

    public void shutdown() {
        poller.shutdownNow();
    }

    public CompletableFuture<Long> buyRise(Contract contract) {
        return buyAck("CALL", contract).thenApply(BuyAck::contractId);
    }

    public CompletableFuture<Long> buyFall(Contract contract) {
        return buyAck("PUT", contract).thenApply(BuyAck::contractId);
    }

    public enum BuySellResult { SUCCESS, FAIL }

    public CompletableFuture<BuySellResult> buySellAndAwait(Contract contract) {
        Objects.requireNonNull(contract, "contract");

        CompletableFuture<Long> callF = buyRise(contract);
        CompletableFuture<Long> putF  = buyFall(contract);

        return callF.thenCombine(putF, (callId, putId) -> new long[]{callId, putId})
                .thenCompose(ids -> awaitBothSoldRetryForever(contract.symbol(), ids[0], ids[1]));
    }

    private CompletableFuture<BuySellResult> awaitBothSoldRetryForever(String symbol, long callId, long putId) {
        CompletableFuture<BuySellResult> out = new CompletableFuture<>();
        awaitAttempt(symbol, callId, putId, out);
        return out;
    }

    private void awaitAttempt(String symbol, long callId, long putId, CompletableFuture<BuySellResult> out) {
        if (out.isDone()) return;

        awaitBothSold(symbol, callId, putId).whenComplete((res, ex) -> {
            if (ex == null) {
                out.complete(res);
                return;
            }

            Throwable t = unwrapCompletionException(ex);

            if (t instanceof TimeoutException) {
                log.accept("🟧 AWAIT TIMEOUT symbol=" + symbol
                        + " callId=" + callId + " putId=" + putId
                        + " => retry polling in " + AWAIT_RETRY_DELAY_MILLIS + "ms");

                delay(AWAIT_RETRY_DELAY_MILLIS).whenComplete((v, delayEx) -> {
                    if (delayEx != null) {
                        out.completeExceptionally(unwrapCompletionException(delayEx));
                        return;
                    }
                    awaitAttempt(symbol, callId, putId, out);
                });
                return;
            }

            out.completeExceptionally(t);
        });
    }

    private CompletableFuture<Void> delay(long millis) {
        if (millis <= 0) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> cf = new CompletableFuture<>();
        poller.schedule(() -> cf.complete(null), millis, TimeUnit.MILLISECONDS);
        return cf;
    }

    private CompletableFuture<BuySellResult> awaitBothSold(String symbol, long callId, long putId) {
        Objects.requireNonNull(symbol, "symbol");

        CompletableFuture<BuySellResult> out = new CompletableFuture<>();

        AtomicReference<PocState> call = new AtomicReference<>();
        AtomicReference<PocState> put  = new AtomicReference<>();

        AtomicBoolean callInFlight = new AtomicBoolean(false);
        AtomicBoolean putInFlight  = new AtomicBoolean(false);

        AtomicInteger callErrCount = new AtomicInteger(0);
        AtomicInteger putErrCount  = new AtomicInteger(0);
        AtomicReference<String> callLastErr = new AtomicReference<>(null);
        AtomicReference<String> putLastErr  = new AtomicReference<>(null);

        long startMs = System.currentTimeMillis();

        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        Runnable tick = () -> {
            if (out.isDone()) return;

            long elapsed = System.currentTimeMillis() - startMs;
            if (elapsed > AWAIT_TIMEOUT_MILLIS) {
                out.completeExceptionally(new TimeoutException(
                        "buySellAndAwait timeout. symbol=" + symbol + " callId=" + callId + " putId=" + putId
                                + " callErrs=" + callErrCount.get() + " putErrs=" + putErrCount.get()
                ));
                return;
            }

            PocState cs = call.get();
            if ((cs == null || !cs.sold) && callInFlight.compareAndSet(false, true)) {
                pollOneContract(symbol, callId, "CALL")
                        .whenComplete((st, ex) -> {
                            try {
                                if (ex != null) {
                                    int n = callErrCount.incrementAndGet();
                                    String msg = formatErr(ex);
                                    callLastErr.set(msg);
                                    log.accept("🟥 POC ERROR CALL symbol=" + symbol + " contractId=" + callId
                                            + " count=" + n + " err=" + msg);
                                } else {
                                    call.set(st);
                                    maybeComplete(symbol, callId, putId, call.get(), put.get(),
                                            callErrCount.get(), putErrCount.get(),
                                            callLastErr.get(), putLastErr.get(),
                                            out);
                                }
                            } finally {
                                callInFlight.set(false);
                            }
                        });
            }

            PocState ps = put.get();
            if ((ps == null || !ps.sold) && putInFlight.compareAndSet(false, true)) {
                pollOneContract(symbol, putId, "PUT")
                        .whenComplete((st, ex) -> {
                            try {
                                if (ex != null) {
                                    int n = putErrCount.incrementAndGet();
                                    String msg = formatErr(ex);
                                    putLastErr.set(msg);
                                    log.accept("🟥 POC ERROR PUT symbol=" + symbol + " contractId=" + putId
                                            + " count=" + n + " err=" + msg);
                                } else {
                                    put.set(st);
                                    maybeComplete(symbol, callId, putId, call.get(), put.get(),
                                            callErrCount.get(), putErrCount.get(),
                                            callLastErr.get(), putLastErr.get(),
                                            out);
                                }
                            } finally {
                                putInFlight.set(false);
                            }
                        });
            }
        };

        ScheduledFuture<?> f = poller.scheduleAtFixedRate(tick, 0, AWAIT_POLL_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        futureRef.set(f);

        out.whenComplete((r, ex) -> {
            ScheduledFuture<?> ff = futureRef.get();
            if (ff != null) ff.cancel(false);
        });

        return out;
    }

    private void maybeComplete(
            String symbol,
            long callId,
            long putId,
            PocState call,
            PocState put,
            int callErrs,
            int putErrs,
            String callLastErr,
            String putLastErr,
            CompletableFuture<BuySellResult> out
    ) {
        if (out.isDone()) return;
        if (call == null || put == null) return;
        if (!call.sold || !put.sold) return;

        boolean success = (call.profit > 0.0) || (put.profit > 0.0);

        log.accept("🟩 DONE symbol=" + symbol
                + " callId=" + callId + " status=" + call.status + " profit=" + call.profit + " expired=" + (call.expired ? 1 : 0)
                + " | putId=" + putId + " status=" + put.status + " profit=" + put.profit + " expired=" + (put.expired ? 1 : 0)
                + " | pollErrors(call=" + callErrs + (callLastErr != null ? (", last=" + callLastErr) : "")
                + ", put=" + putErrs + (putLastErr != null ? (", last=" + putLastErr) : "") + ")"
                + " => " + (success ? "SUCCESS" : "FAIL"));

        out.complete(success ? BuySellResult.SUCCESS : BuySellResult.FAIL);
    }

    private CompletableFuture<PocState> pollOneContract(String symbol, long contractId, String tag) {
        ObjectNode req = mapper.createObjectNode();
        req.put("proposal_open_contract", 1);
        req.put("contract_id", contractId);
        req.put("req_id", reqSeq.getAndIncrement());

        return ws().sendRequest(req).thenApply(resp -> {
            JsonNode err = resp.path("error");
            if (!err.isMissingNode() && !err.isNull()) {
                throw new IllegalStateException("POC error. tag=" + tag + " symbol=" + symbol + " id=" + contractId + " err=" + err);
            }

            JsonNode poc = resp.path("proposal_open_contract");
            if (poc.isMissingNode() || poc.isNull()) {
                throw new IllegalStateException("POC missing proposal_open_contract. tag=" + tag + " symbol=" + symbol + " id=" + contractId);
            }

            boolean sold = poc.path("is_sold").asInt(0) == 1;
            boolean expired = poc.path("is_expired").asInt(0) == 1;
            String status = poc.path("status").asText("");
            double profit = poc.path("profit").asDouble(0.0);

            return new PocState(sold, expired, status, profit, resp);
        });
    }

    private CompletableFuture<BuyAck> buyAck(String contractType, Contract contract) {
        try {
            if (derivCurrencyHolder.getCurrency().isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Currency not set"));
            }
            if (connectorHolder.getConnector().isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Deriv connector not set"));
            }

            Objects.requireNonNull(contract, "contract");

            ObjectNode params = mapper.createObjectNode();
            params.put("amount", contract.stake());
            params.put("basis", contract.basis());
            params.put("contract_type", contractType);
            params.put("currency", derivCurrencyHolder.getCurrency().get());
            params.put("duration", contract.durationTicks());
            params.put("duration_unit", contract.durationUnit());
            params.put("symbol", contract.symbol());

            ObjectNode buy = mapper.createObjectNode();
            buy.put("buy", 1);
            buy.put("price", contract.stake());
            buy.set("parameters", params);

            return ws().sendRequest(buy).thenApply(this::extractBuyAck);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private BuyAck extractBuyAck(JsonNode resp) {
        if (resp == null) throw new IllegalStateException("Null response");

        JsonNode err = resp.path("error");
        if (!err.isMissingNode() && !err.isNull()) {
            throw new IllegalStateException("Deriv error in buy: " + err);
        }

        JsonNode buy = resp.path("buy");
        long contractId = buy.path("contract_id").asLong(-1);
        if (contractId <= 0) throw new IllegalStateException("No buy.contract_id. resp=" + resp);

        Long txId = buy.path("transaction_id").isMissingNode() || buy.path("transaction_id").isNull()
                ? null
                : buy.path("transaction_id").asLong();

        return new BuyAck(contractId, txId);
    }

    private DerivConnector ws() {
        return connectorHolder.getConnector()
                .orElseThrow(() -> new IllegalStateException("Deriv connector not set"));
    }

    private static String formatErr(Throwable ex) {
        Throwable t = unwrapCompletionException(ex);
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) msg = t.toString();
        return t.getClass().getSimpleName() + ": " + msg;
    }

    private static Throwable unwrapCompletionException(Throwable ex) {
        Throwable t = ex;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private record BuyAck(long contractId, Long transactionId) { }

    private record PocState(boolean sold, boolean expired, String status, double profit, JsonNode payload) { }
}
