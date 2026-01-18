package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoosool.model.Contract;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DerivTradingService {

    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);

    private final ObjectMapper mapper = new ObjectMapper();
    private final DerivConnectorHolder connectorHolder;
    private final DerivCurrencyHolder derivCurrencyHolder;

    // Single thread is enough: polling is very light.
    // IMPORTANT: you should shutdown it in Application.stop() / container registry.
    private final ScheduledExecutorService statusPoller =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "deriv-contract-status-poller");
                t.setDaemon(true);
                return t;
            });

    public DerivTradingService(DerivConnectorHolder connectorHolder, DerivCurrencyHolder derivCurrencyHolder) {
        this.connectorHolder = Objects.requireNonNull(connectorHolder, "connectorHolder");
        this.derivCurrencyHolder = Objects.requireNonNull(derivCurrencyHolder, "derivCurrencyHolder");
    }

    public CompletableFuture<Long> buyRise(Contract contract) {
        return buy("CALL", contract);
    }

    public CompletableFuture<Long> buyFall(Contract contract) {
        return buy("PUT", contract);
    }

    public CompletableFuture<Void> buyBoth(Contract contract) {
        CompletableFuture<Long> up = buyRise(contract);
        CompletableFuture<Long> down = buyFall(contract);
        return CompletableFuture.allOf(up, down);
    }

    /**
     * Buys both sides and asynchronously waits until BOTH contracts are closed.
     * No blocking here. Caller can attach thenAccept / whenComplete.
     */
    public CompletableFuture<BothBuyStatus> buyBothAndWaitAsync(Contract contract) {
        CompletableFuture<Long> upF = buyRise(contract);
        CompletableFuture<Long> downF = buyFall(contract);

        return upF.thenCombine(downF, (upId, downId) -> new long[]{upId, downId})
                .thenCompose(ids -> {
                    long upId = ids[0];
                    long downId = ids[1];

                    CompletableFuture<ContractOutcome> upClosed =
                            waitUntilClosedByPolling(upId, DEFAULT_WAIT_TIMEOUT, DEFAULT_POLL_INTERVAL);

                    CompletableFuture<ContractOutcome> downClosed =
                            waitUntilClosedByPolling(downId, DEFAULT_WAIT_TIMEOUT, DEFAULT_POLL_INTERVAL);

                    return upClosed.thenCombine(downClosed, (up, down) ->
                            new BothBuyStatus(up.contractId(), down.contractId(), up, down, Instant.now())
                    );
                });
    }

    /**
     * One-shot contract status by id (no streaming).
     */
    public CompletableFuture<ContractOutcome> getContractOutcomeOnce(long contractId) {
        ObjectNode req = mapper.createObjectNode();
        req.put("proposal_open_contract", 1);
        req.put("contract_id", contractId);

        return ws().sendRequest(req).thenApply(resp -> parseOutcomeFromProposalOpenContract(contractId, resp));
    }

    /**
     * Poll proposal_open_contract until contract is sold/expired.
     */
    public CompletableFuture<ContractOutcome> waitUntilClosedByPolling(
            long contractId,
            Duration timeout,
            Duration pollInterval
    ) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");

        CompletableFuture<ContractOutcome> result = new CompletableFuture<>();
        Instant deadline = Instant.now().plus(timeout);

        AtomicBoolean running = new AtomicBoolean(true);

        Runnable tick = () -> {
            if (!running.get()) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                running.set(false);
                result.completeExceptionally(new TimeoutException("Contract " + contractId + " not closed within " + timeout));
                return;
            }

            getContractOutcomeOnce(contractId).whenComplete((outcome, ex) -> {
                if (!running.get()) {
                    return;
                }
                if (ex != null) {
                    running.set(false);
                    result.completeExceptionally(ex);
                    return;
                }

                if (outcome.isClosed()) {
                    running.set(false);
                    result.complete(outcome);
                }
                // else keep polling
            });
        };

        ScheduledFuture<?> sf = statusPoller.scheduleAtFixedRate(
                tick,
                0,
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );

        // Stop the scheduler task when result completes (anyhow)
        result.whenComplete((ok, ex) -> sf.cancel(false));

        return result;
    }

    private CompletableFuture<Long> buy(String contractType, Contract contract) {
        if (derivCurrencyHolder.getCurrency().isEmpty()) {
            throw new IllegalStateException("Currency not set");
        }
        if (connectorHolder.getConnector().isEmpty()) {
            throw new IllegalStateException("Deriv connector not set");
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

        return ws().sendRequest(buy).thenApply(this::extractContractId);
    }

    private DerivConnector ws() {
        return connectorHolder.getConnector()
                .orElseThrow(() -> new IllegalStateException("Deriv connector not set"));
    }

    private long extractContractId(JsonNode resp) {
        long contractId = resp.path("buy").path("contract_id").asLong(-1);
        if (contractId <= 0) {
            throw new IllegalStateException("No buy.contract_id. resp=" + resp);
        }
        return contractId;
    }

    private ContractOutcome parseOutcomeFromProposalOpenContract(long contractId, JsonNode resp) {
        JsonNode poc = resp.path("proposal_open_contract");
        if (poc.isMissingNode() || poc.isNull()) {
            throw new IllegalStateException("No proposal_open_contract in resp=" + resp);
        }

        // Deriv typically returns: is_sold: 0/1, status: "open"/"won"/"lost", profit, payout, etc.
        boolean isSold = poc.path("is_sold").asInt(0) == 1;

        String status = Optional.ofNullable(poc.path("status").asText(null)).orElse("unknown");
        double profit = poc.path("profit").asDouble(Double.NaN);

        // "won" / "lost" are common end states.
        boolean isWin = "won".equalsIgnoreCase(status);

        // If closed but status unknown, treat as not win (conservative) but still closed.
        return new ContractOutcome(contractId, isSold, isWin, status, profit);
    }

    public record BothBuyStatus(
            long riseContractId,
            long fallContractId,
            ContractOutcome rise,
            ContractOutcome fall,
            Instant completedAt
    ) {
        public boolean isSuccess() {
            // Your rule: if at least one is win => success
            return (rise != null && rise.isWin()) || (fall != null && fall.isWin());
        }
    }

    public record ContractOutcome(
            long contractId,
            boolean isClosed,
            boolean isWin,
            String status,
            double profit
    ) {}
}
