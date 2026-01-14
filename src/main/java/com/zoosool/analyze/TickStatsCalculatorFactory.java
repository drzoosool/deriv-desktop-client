package com.zoosool.analyze;

@FunctionalInterface
public interface TickStatsCalculatorFactory {
    TickStatsCalculator create(String symbol);
}
