package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoosool.model.Contract;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class DerivTradingService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DerivConnector connector;
    private final String currency;

    public DerivTradingService(DerivConnector connector, String currency) {
        this.connector = Objects.requireNonNull(connector, "connector");
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is blank");
        }
        this.currency = currency;
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

    private CompletableFuture<Long> buy(String contractType, Contract contract) {
        Objects.requireNonNull(contract, "contract");

        ObjectNode params = mapper.createObjectNode();
        params.put("amount", contract.stake());
        params.put("basis", contract.basis());
        params.put("contract_type", contractType);
        params.put("currency", currency);
        params.put("duration", contract.durationTicks());
        params.put("duration_unit", contract.durationUnit());
        params.put("symbol", contract.symbol());

        ObjectNode buy = mapper.createObjectNode();
        buy.put("buy", 1);
        buy.put("price", contract.stake());
        buy.set("parameters", params);

        // Trading does not reconnect. It relies on connector state.
        return connector.sendRequest(buy).thenApply(this::extractContractId);
    }

    private long extractContractId(JsonNode resp) {
        long contractId = resp.path("buy").path("contract_id").asLong(-1);
        if (contractId <= 0) {
            throw new IllegalStateException("No buy.contract_id: " + resp);
        }
        return contractId;
    }
}
