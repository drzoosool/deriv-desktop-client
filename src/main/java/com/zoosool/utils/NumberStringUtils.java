package com.zoosool.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class NumberStringUtils {
    private NumberStringUtils() {}

    /**
     * Parses decimal string into long using fixed scale:
     * valueLong = value * 10^scale (rounded if needed).
     *
     * Examples (scale=2):
     *  "123"     -> 12300
     *  "123.0"   -> 12300
     *  "123.5"   -> 12350
     *  "123.50"  -> 12350
     *  ".5"      -> 50
     */
    public static long toScaledLong(String s, int scale) {
        if (s == null) throw new IllegalArgumentException("s is null");
        String t = s.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("s is blank");
        if (scale < 0 || scale > 12) throw new IllegalArgumentException("scale out of range: " + scale);

        try {
            BigDecimal bd = new BigDecimal(t);

            // normalize to fixed scale
            bd = bd.setScale(scale, RoundingMode.HALF_UP);

            // shift decimal point and require exact integer
            bd = bd.movePointRight(scale);

            try {
                return bd.longValueExact();
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("value out of range for long: " + s, ex);
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid decimal format: " + s, ex);
        }
    }
}
