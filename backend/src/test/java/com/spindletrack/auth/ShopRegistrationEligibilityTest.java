package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ShopRegistrationEligibilityTest {
    private final ShopRegistrationEligibility eligibility = new ShopRegistrationEligibility();

    @Test
    void unassociatedCustomerWithoutOrdersCanRegister() {
        assertDoesNotThrow(() -> eligibility.requireEligible(account("CUSTOMER", null), false));
    }

    @Test
    void customerWithAttachedOrdersMustUseAnotherAccount() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> eligibility.requireEligible(account("CUSTOMER", null), true));
        assertEquals("Please use another account.", exception.getReason());
    }

    @Test
    void ownerStaffAndPlatformAdminCannotRegisterAnotherShop() {
        for (String role : new String[] {"OWNER", "STAFF", "PLATFORM_ADMIN"}) {
            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> eligibility.requireEligible(account(role, 1L), false));
            assertEquals("Please use another account.", exception.getReason());
        }
    }

    @Test
    void customerAlreadyAssociatedWithAShopCannotRegisterAnotherShop() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> eligibility.requireEligible(account("CUSTOMER", 1L), false));
        assertEquals("Please use another account.", exception.getReason());
    }

    private static UserAccount account(String role, Long shopId) {
        return new UserAccount(1L, "firebase-uid", "Test User", "test@example.com", role, true, shopId, null);
    }
}
