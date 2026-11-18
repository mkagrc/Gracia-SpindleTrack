package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShopDocumentAccessPolicyTest {
    private final ShopDocumentAccessPolicy policy = new ShopDocumentAccessPolicy();

    @Test
    void platformAdministratorMayOpenAnyShopDocument() {
        assertTrue(policy.canOpen("PLATFORM_ADMIN", 900L, 41L));
    }

    @Test
    void onlyTheOwnerWhoUploadedMayOpenAsAnOwner() {
        assertTrue(policy.canOpen("OWNER", 41L, 41L));
        assertFalse(policy.canOpen("OWNER", 42L, 41L));
        assertFalse(policy.canOpen("OWNER", null, 41L));
    }

    @Test
    void staffAndCustomersCannotOpenShopDocuments() {
        assertFalse(policy.canOpen("STAFF", 41L, 41L));
        assertFalse(policy.canOpen("CUSTOMER", 41L, 41L));
    }
}
