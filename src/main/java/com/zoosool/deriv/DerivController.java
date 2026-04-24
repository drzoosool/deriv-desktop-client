package com.zoosool.deriv;

import com.zoosool.model.Contract;

import java.util.Objects;
import java.util.function.Consumer;

public class DerivController implements DerivOperations {

    private final Consumer<String> log;
    private final DerivTradingService trading;

    private static final int DELAY_TIME_MILLIS = 1500;

    public DerivController(DerivTradingService tradingService, Consumer<String> uiLog) {
        this.trading = Objects.requireNonNull(tradingService, "tradingService");
        this.log = Objects.requireNonNull(uiLog, "uiLog");
    }

    @Override
    public void buy(Contract contract) {
        trading.buyRise(contract)
                .thenAccept(id -> log.accept("BUY (Rise) OK contract_id=" + id))
                .exceptionally(ex -> {
                    log.accept("BUY (Rise) FAIL: " + rootMessage(ex));
                    return null;
                });
    }

    @Override
    public void sell(Contract contract) {
        trading.buyFall(contract)
                .thenAccept(id -> log.accept("DOWN (Fall) OK contract_id=" + id))
                .exceptionally(ex -> {
                    log.accept("DOWN (Fall) FAIL: " + rootMessage(ex));
                    return null;
                });
    }

    @Override
    public void buySell(Contract contract) {
        trading.buyBoth(contract)
                .thenRun(() -> log.accept("BUY+DOWN sent"))
                .exceptionally(ex -> {
                    log.accept("BUY+DOWN FAIL: " + rootMessage(ex));
                    return null;
                });
    }

    @Override
    public void buySellS(Contract contract) {
        trading.buyBoth(contract, true)
                .thenRun(() -> log.accept("BUY+DOWN SMART sent"))
                .exceptionally(ex -> {
                    log.accept("BUY+DOWN FAIL: " + rootMessage(ex));
                    return null;
                });
    }

    @Override
    public void buySellD(Contract contract) {
        trading.buyRise(contract)
                .thenCompose(id -> trading.delay(DELAY_TIME_MILLIS))
                .thenCompose(id -> trading.buyFall(new Contract(contract.symbol(), contract.stake(), contract.durationTicks() - 2,
                        contract.durationUnit(), contract.basis())))
                .thenRun(() -> log.accept("BUY+DOWN SMART sent"))
                .exceptionally(ex -> {
                    log.accept("BUY+DOWN FAIL: " + rootMessage(ex));
                    return null;
                });
    }

    @Override
    public void sellBuyD(Contract contract) {
        trading.buyFall(contract)
                .thenCompose(id -> trading.delay(DELAY_TIME_MILLIS))
                .thenCompose(id -> trading.buyRise(new Contract(contract.symbol(), contract.stake(), contract.durationTicks() - 2,
                        contract.durationUnit(), contract.basis())))
                .thenRun(() -> log.accept("BUY+DOWN SMART sent"))
                .exceptionally(ex -> {
                    log.accept("BUY+DOWN FAIL: " + rootMessage(ex));
                    return null;
                });
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }
}
