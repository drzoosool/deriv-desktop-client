package com.zoosool.deriv;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class DefaultConnectionStateController implements ConnectionStateController {

    private final AtomicReference<ConnectionState> state;
    private final CopyOnWriteArrayList<BiConsumer<ConnectionState, ConnectionState>> listeners = new CopyOnWriteArrayList<>();

    private final Executor notifyExecutor;
    private final Consumer<String> log;

    public DefaultConnectionStateController(
            ConnectionState initial,
            Executor notifyExecutor,
            Consumer<String> log
    ) {
        this.state = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.notifyExecutor = Objects.requireNonNull(notifyExecutor, "notifyExecutor");
        this.log = (log != null) ? log : s -> {};
    }

    @Override
    public ConnectionState get() {
        return state.get();
    }

    @Override
    public boolean compareAndSet(ConnectionState expected, ConnectionState update, String reason) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(update, "update");

        ConnectionState prev = state.get();
        if (prev != expected) return false;

        boolean changed = state.compareAndSet(expected, update);
        if (changed) {
            notifyChanged(expected, update, reason);
        }
        return changed;
    }

    @Override
    public void set(ConnectionState update, String reason) {
        Objects.requireNonNull(update, "update");

        ConnectionState prev = state.getAndSet(update);
        if (prev != update) {
            notifyChanged(prev, update, reason);
        }
    }

    @Override
    public void addListener(BiConsumer<ConnectionState, ConnectionState> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void notifyChanged(ConnectionState prev, ConnectionState next, String reason) {
        // Log transition (optional)
        log.accept("[" + Instant.now() + "] STATE " + prev + " -> " + next
                + (reason != null && !reason.isBlank() ? " (" + reason + ")" : ""));

        // Notify listeners on requested executor (e.g. Platform::runLater for JavaFX)
        for (BiConsumer<ConnectionState, ConnectionState> l : listeners) {
            notifyExecutor.execute(() -> l.accept(prev, next));
        }
    }
}
