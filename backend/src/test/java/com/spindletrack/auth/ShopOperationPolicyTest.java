package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ShopOperationPolicyTest {
    private static final List<String> SHOP_OPERATION_PATHS = List.of(
            "/api/orders", "/api/services", "/api/staff-invitations", "/api/reports");
    private static final List<String> INACTIVE_STATUSES = List.of("PENDING", "REJECTED", "DEACTIVATED");

    @Test
    void allShopOperationsAreBlockedForOwnersAndStaffUntilTheShopIsActive() {
        for (String role : List.of("OWNER", "STAFF")) {
            for (String status : INACTIVE_STATUSES) {
                for (String path : SHOP_OPERATION_PATHS) {
                    assertFalse(ShopOperationPolicy.mayUseShopOperations(role, status, "GET", path),
                            role + " with " + status + " shop should be blocked from " + path);
                }
            }
        }
    }

    @Test
    void activeOwnersAndStaffMayUseShopOperations() {
        for (String role : List.of("OWNER", "STAFF")) {
            for (String path : SHOP_OPERATION_PATHS) {
                assertTrue(ShopOperationPolicy.mayUseShopOperations(role, "ACTIVE", "GET", path));
            }
        }
    }

    @Test
    void inactiveOwnersCanSeeStatusEditRejectedRegistrationsAndOpenTheirDocuments() {
        assertTrue(ShopOperationPolicy.mayUseShopOperations(
                "OWNER", "REJECTED", "GET", "/api/shops/mine/registration"));
        assertTrue(ShopOperationPolicy.mayUseShopOperations(
                "OWNER", "REJECTED", "PUT", "/api/shops/mine/registration"));
        assertTrue(ShopOperationPolicy.mayUseShopOperations(
                "OWNER", "REJECTED", "DELETE", "/api/shops/mine/registration/documents/12"));
        assertTrue(ShopOperationPolicy.mayUseShopOperations(
                "OWNER", "PENDING", "GET", "/api/shops/documents/12"));
    }

    @Test
    void activeCustomersAndPlatformAdminsAreNotAffectedByShopStatus() {
        for (String role : List.of("CUSTOMER", "PLATFORM_ADMIN")) {
            for (String status : INACTIVE_STATUSES) {
                assertTrue(ShopOperationPolicy.mayUseShopOperations(role, status, "GET", "/api/reports"));
            }
        }
    }
}
