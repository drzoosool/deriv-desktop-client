package com.zoosool.model;

import java.time.Instant;
import java.util.Objects;

public record TickSample(
        Instant at,
        double quote
) {
    public TickSample {
        Objects.requireNonNull(at, "at");
    }
}
