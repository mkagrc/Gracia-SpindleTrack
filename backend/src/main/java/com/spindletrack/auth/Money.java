package com.spindletrack.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Canonical PHP money representation for Firestore and reconciliation: integer centavos. */
public final class Money {
    private Money() {}

    public static long toCentavos(BigDecimal amount) {
        if (amount == null) throw new IllegalArgumentException("Money amount is required.");
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    public static BigDecimal fromCentavos(long centavos) {
        return BigDecimal.valueOf(centavos, 2).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal fromFirestore(Object centavos, Object legacyAmount) {
        if (centavos instanceof Number number) return fromCentavos(number.longValue());
        if (legacyAmount instanceof BigDecimal decimal) return decimal.setScale(2, RoundingMode.HALF_UP);
        if (legacyAmount instanceof Number number)
            return new BigDecimal(number.toString()).setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal fromPriceLog(String unit, Object value) {
        if (value == null) return null;
        if ("CENTAVOS".equals(unit) && value instanceof Number number) return fromCentavos(number.longValue());
        if (value instanceof BigDecimal decimal) return decimal.setScale(2, RoundingMode.HALF_UP);
        if (value instanceof Number number) return new BigDecimal(number.toString()).setScale(2, RoundingMode.HALF_UP);
        return null;
    }
}
