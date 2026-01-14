package com.zoosool.analyze;

import com.zoosool.model.TickEvent;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class TickEventRouterService {

    private final Consumer<String> log;
    private final ExecutorService workersExecutor;
    private final TickStatsCalculatorFactory calculatorFactory;
    private final int queueCapacity;

    private final ConcurrentHashMap<String, WorkerContext> workersBySymbol = new ConcurrentHashMap<>();

    public TickEventRouterService(
            Consumer<String> log,
            ExecutorService workersExecutor,
            TickStatsCalculatorFactory calculatorFactory,
            int queueCapacity
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.workersExecutor = Objects.requireNonNull(workersExecutor, "workersExecutor");
        this.calculatorFactory = Objects.requireNonNull(calculatorFactory, "calculatorFactory");
        if (queueCapacity < 16) {
            throw new IllegalArgumentException("queueCapacity is too small: " + queueCapacity);
        }
        this.queueCapacity = queueCapacity;
    }

    public void onTick(String symbol, double quote) {
        WorkerContext ctx = getOrCreateWorker(symbol);

        TickEvent event = TickEvent.tick(symbol, quote);

        boolean enqueued = ctx.queue.offer(event);
        if (!enqueued) {
            ctx.worker.incrementDropped();
        }
    }

    public void onResetSymbol(String symbol, String reason) {
        WorkerContext ctx = getOrCreateWorker(symbol);

        ctx.queue.clear();
        boolean ok = ctx.queue.offer(TickEvent.reset(symbol));
        if (!ok) {
            ctx.worker.incrementDropped();
        }
        log.accept("[STATS] symbol=" + symbol + " state=RESET reason=" + reason);
    }

    public void onResetAll(String reason) {
        workersBySymbol.forEach((symbol, ctx) -> {
            ctx.queue.clear();
            ctx.queue.offer(TickEvent.reset(symbol));
        });
        log.accept("[STATS] state=RESET_ALL reason=" + reason + " symbols=" + workersBySymbol.size());
    }

    public void stopAllAndShutdown(DurationLike timeout) {
        workersBySymbol.forEach((symbol, ctx) -> {
            ctx.queue.clear();
            ctx.queue.offer(TickEvent.stop(symbol));
        });

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        workersBySymbol.forEach((symbol, ctx) -> {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) return;

            try {
                ctx.future.get(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Best-effort; we'll shutdown executor below
            }
        });

        // You own the executor; shut it down here.
        workersExecutor.shutdownNow();
        workersBySymbol.clear();
        log.accept("[STATS] state=STOP_ALL done");
    }

    private WorkerContext getOrCreateWorker(String symbol) {
        return workersBySymbol.computeIfAbsent(symbol, this::createAndStartWorker);
    }

    private WorkerContext createAndStartWorker(String symbol) {
        BlockingQueue<TickEvent> queue = new ArrayBlockingQueue<>(queueCapacity);

        TickStatsCalculator calc = calculatorFactory.create(symbol);
        TickSymbolWorker worker = new TickSymbolWorker(symbol, queue, calc, log);

        Future<?> future = workersExecutor.submit(worker);

        log.accept("[STATS] symbol=" + symbol + " state=WORKER_STARTED queueCap=" + queueCapacity);
        return new WorkerContext(queue, worker, future);
    }

    private record WorkerContext(
            BlockingQueue<TickEvent> queue,
            TickSymbolWorker worker,
            Future<?> future
    ) { }

    /**
     * Tiny helper so you can avoid pulling Duration here if you don't want.
     * Replace with java.time.Duration if you prefer.
     */
    public interface DurationLike {
        long toNanos();

        static DurationLike seconds(long seconds) {
            return () -> TimeUnit.SECONDS.toNanos(seconds);
        }
    }
}
