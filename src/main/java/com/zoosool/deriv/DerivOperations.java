package com.zoosool.deriv;

import com.zoosool.model.Contract;

public interface DerivOperations {
    void sell(Contract contract);
    void buy(Contract contract);
    void buySell(Contract contract);
}
