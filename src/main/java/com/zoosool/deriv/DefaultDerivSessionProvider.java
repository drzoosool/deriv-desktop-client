package com.zoosool.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoosool.model.ActiveSymbol;
import com.zoosool.model.DerivSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class DefaultDerivSessionProvider implements DerivSessionProvider {

    private final DerivWsClient ws;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile CompletableFuture<DerivSession> readyFuture;

    public DefaultDerivSessionProvider(DerivWsClient ws) {
        this.ws = ws;
    }

    public void connect() {
        ws.connect();
    }

    @Override
    public CompletableFuture<DerivSession> ready() {
        if (readyFuture != null) return readyFuture;

        readyFuture = ws.authorized()
                .thenApply(authResp -> {
                    String currency = authResp.path("authorize").path("currency").asText(null);
                    if (currency == null || currency.isBlank()) {
                        throw new IllegalStateException("Cannot detect account currency from authorize response");
                    }
                    return currency;
                })
                .thenCompose(currency ->
                        loadActiveSymbols().thenApply(list -> new DerivSession(currency, list))
                );

        return readyFuture;
    }

    private CompletableFuture<List<ActiveSymbol>> loadActiveSymbols() {
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
            if (out.isEmpty()) throw new IllegalStateException("active_symbols is empty");
            return out;
        });
    }

    @Override
    public DerivWsClient ws() {
        return ws;
    }

    @Override
    public void close() {
        // ws.close() если есть
    }
}
