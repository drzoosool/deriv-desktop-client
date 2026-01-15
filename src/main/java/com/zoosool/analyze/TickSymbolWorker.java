package com.zoosool.analyze;

import com.zoosool.enums.TickAction;
import com.zoosool.model.TickEvent;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class TickSymbolWorker implements Runnable {

    private final String symbol;
    private final BlockingQueue<TickEvent> queue;
    private final TickStatsCalculator calculator;
    private final Consumer<String> log;

    private final AtomicLong droppedCounter = new AtomicLong(0);

    private final long droppedLogEveryNanos = TimeUnit.SECONDS.toNanos(10);
    private volatile long nextDroppedLogAtNanos = System.nanoTime() + droppedLogEveryNanos;

    public TickSymbolWorker(
            String symbol,
            BlockingQueue<TickEvent> queue,
            TickStatsCalculator calculator,
            Consumer<String> log
    ) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.log = Objects.requireNonNull(log, "log");
    }

    public void incrementDropped() {
        droppedCounter.incrementAndGet();
    }

    @Override
    public void run() {
        calculator.onStart();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                TickEvent event = queue.take();

                calculator.onEvent(event);

                maybeLogDropped();

                if (event.action() == TickAction.STOP) {
                    log.accept("[STATS] symbol=" + symbol + " state=WORKER_STOP");
                    return;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.accept("[STATS] symbol=" + symbol + " state=WORKER_CRASH err=" + t);
        } finally {
            try {
                maybeLogDroppedForce();
                calculator.onStop();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private void maybeLogDropped() {
        long now = System.nanoTime();
        if (now < nextDroppedLogAtNanos) {
            return;
        }
        nextDroppedLogAtNanos = now + droppedLogEveryNanos;
        maybeLogDroppedForce();
    }

    private void maybeLogDroppedForce() {
        long dropped = droppedCounter.getAndSet(0);
        if (dropped > 0) {
            log.accept("[STATS] symbol=" + symbol + " dropped=" + dropped);
        }
    }
}
