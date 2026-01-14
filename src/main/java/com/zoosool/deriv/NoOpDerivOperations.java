package com.zoosool.deriv;

import com.zoosool.model.Contract;

public class NoOpDerivOperations implements DerivOperations {
    @Override public void buy(Contract contract) {}
    @Override public void sell(Contract contract) {}
    @Override public void buySell(Contract contract) {}
}
