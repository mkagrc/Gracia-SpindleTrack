package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopAddOnCatalogTest {
    @Test
    void acceptsFreeAddOnAndOptionalUntrackedInventory() {
        assertDoesNotThrow(() -> ShopAddOnCatalog.validate(new ShopAddOnCatalog.AddOnRequest(
                "Free detergent", "Detergent", "sachet", BigDecimal.ZERO, null, null, false, true, "")));
    }

    @Test
    void requiresStockWhenTrackingAndRejectsInvalidStockOrDeduction() {
        assertBadRequest(new ShopAddOnCatalog.AddOnRequest(
                "Detergent", "Detergent", "sachet", BigDecimal.ONE, null, null, true, true, ""));
        assertBadRequest(new ShopAddOnCatalog.AddOnRequest(
                "Detergent", "Detergent", "sachet", BigDecimal.ONE, -1, 1, true, true, ""));
        assertBadRequest(new ShopAddOnCatalog.AddOnRequest(
                "Detergent", "Detergent", "sachet", BigDecimal.ONE, 5, 0, true, true, ""));
    }

    @Test
    void rejectsUnsupportedCatalogAndMoneyValues() {
        assertBadRequest(new ShopAddOnCatalog.AddOnRequest(
                "Detergent", "Unknown", "sachet", BigDecimal.ONE, null, 1, false, true, ""));
        assertBadRequest(new ShopAddOnCatalog.AddOnRequest(
                "Detergent", "Detergent", "bucket", BigDecimal.ONE, null, 1, false, true, ""));
        assertBadRequest(new ShopAddOnCatalog.AddOnRequest(
                "Detergent", "Detergent", "sachet", new BigDecimal("1.001"), null, 1, false, true, ""));
        assertBadRequest(new ShopAddOnCatalog.AddOnRequest(
                "Detergent", "Detergent", "sachet", new BigDecimal("-0.01"), null, 1, false, true, ""));
    }

    private static void assertBadRequest(ShopAddOnCatalog.AddOnRequest request) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> ShopAddOnCatalog.validate(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
