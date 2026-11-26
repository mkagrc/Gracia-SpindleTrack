package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OrderPricingTest {
    @Test
    void computesPerKilogramSubtotalUsingMinimumWeight() {
        assertEquals(new BigDecimal("30.00"), OrderPricing.serviceSubtotal("PER_KG",
                new BigDecimal("10.00"), new BigDecimal("2.00"), new BigDecimal("3.00")));
        assertEquals(new BigDecimal("25.00"), OrderPricing.serviceSubtotal("PER_KG",
                new BigDecimal("10.00"), new BigDecimal("2.50"), new BigDecimal("2.00")));
    }

    @Test
    void computesPerLoadPerPieceAndFlatSubtotals() {
        assertEquals(new BigDecimal("50.00"), OrderPricing.serviceSubtotal("PER_LOAD",
                new BigDecimal("25.00"), OrderPricing.validateQuantity("PER_LOAD", new BigDecimal("2")), null));
        assertEquals(new BigDecimal("7.50"), OrderPricing.serviceSubtotal("PER_PIECE",
                new BigDecimal("2.50"), OrderPricing.validateQuantity("PER_PIECE", new BigDecimal("3")), null));
        assertEquals(new BigDecimal("9.00"), OrderPricing.serviceSubtotal("FLAT",
                new BigDecimal("9.00"), OrderPricing.validateQuantity("FLAT", BigDecimal.ONE), null));
    }

    @Test
    void roundsComputedAmountsHalfUpToTwoDecimals() {
        assertEquals(new BigDecimal("1.01"), OrderPricing.serviceSubtotal("PER_KG",
                new BigDecimal("0.67"), new BigDecimal("1.50"), null));
        assertEquals(new BigDecimal("1.01"), OrderPricing.lineTotal(new BigDecimal("1.005"), BigDecimal.ONE));
    }

    @Test
    void rejectsFractionalLoadOrPieceAndNonUnitFlatQuantities() {
        assertBadQuantity("PER_LOAD", new BigDecimal("1.5"));
        assertBadQuantity("PER_PIECE", new BigDecimal("2.25"));
        assertBadQuantity("FLAT", new BigDecimal("2"));
        assertBadQuantity("PER_KG", new BigDecimal("1000.00"));
        assertBadQuantity("PER_KG", new BigDecimal("1.001"));
    }

    private static void assertBadQuantity(String type, BigDecimal quantity) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> OrderPricing.validateQuantity(type, quantity));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
