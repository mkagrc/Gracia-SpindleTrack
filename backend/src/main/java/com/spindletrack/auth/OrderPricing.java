package com.spindletrack.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class OrderPricing {
    static final BigDecimal MAX_QUANTITY = new BigDecimal("999.99");
    private static final BigDecimal ONE = BigDecimal.ONE;

    private OrderPricing() {}

    static String billingType(String configured, String legacyUnit) {
        String value = configured == null || configured.isBlank() ? legacyUnit : configured;
        if (value == null || value.isBlank()) return "PER_KG";
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "kg", "per_kg" -> "PER_KG";
            case "load", "per_load" -> "PER_LOAD";
            case "piece", "per_piece" -> "PER_PIECE";
            case "flat" -> "FLAT";
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
    }

    static String unit(String billingType) {
        return switch (billingType) {
            case "PER_LOAD" -> "load";
            case "PER_PIECE" -> "piece";
            case "FLAT" -> "flat";
            default -> "kg";
        };
    }

    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String text && !text.isBlank()) {
            try { return new BigDecimal(text.trim()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    static BigDecimal validateQuantity(String billingType, BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0 || quantity.compareTo(MAX_QUANTITY) > 0
                || quantity.scale() > 2) {
            throw badRequest("Quantity must be greater than zero, no more than 999.99, and have at most 2 decimal places.");
        }
        if (("PER_LOAD".equals(billingType) || "PER_PIECE".equals(billingType))
                && quantity.stripTrailingZeros().scale() > 0) {
            throw badRequest("Number of loads and pieces must be a whole number.");
        }
        if ("FLAT".equals(billingType) && quantity.compareTo(ONE) != 0) {
            throw badRequest("Flat-rate services use a fixed quantity of 1.");
        }
        return quantity.stripTrailingZeros().scale() < 0 ? quantity.setScale(0) : quantity;
    }

    static BigDecimal serviceSubtotal(String billingType, BigDecimal unitPrice, BigDecimal quantity, BigDecimal minimumKg) {
        BigDecimal rate = money(unitPrice);
        BigDecimal billedQuantity = "FLAT".equals(billingType) ? ONE : quantity;
        if ("PER_KG".equals(billingType) && minimumKg != null && minimumKg.compareTo(billedQuantity) > 0)
            billedQuantity = minimumKg;
        return rate.multiply(billedQuantity).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal lineTotal(BigDecimal unitPrice, BigDecimal quantity) {
        return money(unitPrice).multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static long centavos(BigDecimal value) {
        return money(value).movePointRight(2).longValueExact();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
