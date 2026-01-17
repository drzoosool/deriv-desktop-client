package com.zoosool.utils;

/**
 * String/number helpers.
 */
public final class NumberStringUtils {

    private NumberStringUtils() {
        // Utility class
    }

    /**
     * Splits a decimal string by '.', removes trailing zeros from the fractional part,
     * then concatenates integer+fractional parts and parses as long.
     *
     * Examples:
     *  "12.3400" -> 1234
     *  "0.0100"  -> 1
     *  "5.000"   -> 5
     *  "123"     -> 123
     *  ".5"      -> 5   (treated as "0.5")
     */
    public static long toLongByConcatDroppingTrailingZeros(String s) {
        if (s == null) throw new IllegalArgumentException("s is null");

        String t = s.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("s is blank");

        String[] parts = t.split("\\.", -1); // keep empty tail (e.g. "12.")
        if (parts.length > 2) throw new IllegalArgumentException("invalid decimal format: " + s);

        String intPart = parts[0];
        String fracPart = (parts.length == 2) ? parts[1] : "";

        if (intPart.isEmpty()) intPart = "0"; // support ".5"

        if (!intPart.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("invalid integer part: " + s);
        }
        if (!fracPart.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("invalid fractional part: " + s);
        }

        // Drop trailing zeros in fractional part
        int end = fracPart.length();
        while (end > 0 && fracPart.charAt(end - 1) == '0') end--;
        fracPart = fracPart.substring(0, end);

        String concat = intPart + fracPart;
        if (concat.isEmpty()) concat = "0";

        try {
            return Long.parseLong(concat);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("value out of range for long: " + s, ex);
        }
    }
}

