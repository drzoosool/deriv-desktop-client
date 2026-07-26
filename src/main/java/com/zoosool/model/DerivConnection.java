package com.zoosool.model;

import com.zoosool.deriv.DerivWsClient;

public record DerivConnection(DerivWsClient ws, String currency, String accountId) {}

