package com.zoosool.deriv;

import java.util.Optional;

public class DerivConnectorHolder {
    private volatile DerivConnector connector = null;

    public DerivConnectorHolder() {

    }

    public Optional<DerivConnector> getConnector() {
        return Optional.ofNullable(connector);
    }

    public void setConnector(DerivConnector connector) {
        if (connector != null) {
            this.connector = connector;
        }
    }
}
