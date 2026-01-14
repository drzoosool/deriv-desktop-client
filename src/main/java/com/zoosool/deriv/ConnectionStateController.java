package com.zoosool.deriv;

import java.util.function.BiConsumer;

public interface ConnectionStateController {

    ConnectionState get();

    /**
     * Atomically changes state from expected -> update.
     * If changed, listeners are notified.
     */
    boolean compareAndSet(ConnectionState expected, ConnectionState update, String reason);

    /**
     * Sets state unconditionally and notifies listeners if it actually changed.
     */
    void set(ConnectionState update, String reason);

    /**
     * Listener gets (oldState, newState). Called via the controller's notification executor.
     */
    void addListener(BiConsumer<ConnectionState, ConnectionState> listener);
}
