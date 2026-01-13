package com.zoosool.deriv;

import com.zoosool.model.Contract;

import java.math.BigDecimal;

public interface DerivOperations {
    void sell(Contract contract);
    void buy(Contract contract);
    void buySell(Contract contract);
}
