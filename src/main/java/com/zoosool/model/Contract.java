package com.zoosool.model;

import java.math.BigDecimal;

public record Contract(String symbol, BigDecimal stake, int durationTicks, String durationUnit, String basis) {
}
