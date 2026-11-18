package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShopDocumentAccessPolicyTest {
    private final ShopDocumentAccessPolicy policy = new ShopDocumentAccessPolicy();

    @Test
    void platformAdministratorMayOpenAnyShopDocument() {
        assertTrue(policy.canOpen("PLATFORM_ADMIN", "admin-900", "owner-41"));
    }

    @Test
    void onlyTheOwnerWhoUploadedMayOpenAsAnOwner() {
        assertTrue(policy.canOpen("OWNER", "owner-41", "owner-41"));
        assertFalse(policy.canOpen("OWNER", "owner-42", "owner-41"));
        assertFalse(policy.canOpen("OWNER", null, "owner-41"));
    }

    @Test
    void staffAndCustomersCannotOpenShopDocuments() {
        assertFalse(policy.canOpen("STAFF", "owner-41", "owner-41"));
        assertFalse(policy.canOpen("CUSTOMER", "owner-41", "owner-41"));
    }
}
