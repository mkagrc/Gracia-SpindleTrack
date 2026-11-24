package com.spindletrack.auth;

public final class ShopOperationPolicy {
    private ShopOperationPolicy() {
    }

    public static boolean mayUseShopOperations(String role, String shopStatus, String method, String path) {
        if (!"OWNER".equals(role) && !"STAFF".equals(role)) {
            return true;
        }
        if ("ACTIVE".equals(shopStatus)) {
            return true;
        }
        return isRegistrationWorkflow(method, path);
    }

    public static String blockedMessage(String shopStatus) {
        if (shopStatus == null || shopStatus.isBlank()) {
            return "Shop operations are blocked because this account has no ACTIVE shop.";
        }
        return "Shop operations are blocked while the shop is " + shopStatus + ".";
    }

    private static boolean isRegistrationWorkflow(String method, String path) {
        if ("POST".equals(method) && "/api/auth/session".equals(path)) {
            return true;
        }
        if ("GET".equals(method) && "/api/shops/mine/registration".equals(path)) {
            return true;
        }
        if ("PUT".equals(method) && "/api/shops/mine/registration".equals(path)) {
            return true;
        }
        if ("DELETE".equals(method) && path != null
                && path.matches("/api/shops/mine/registration/documents/[^/]+")) {
            return true;
        }
        return "GET".equals(method) && path != null
                && (path.matches("/api/shops/documents/[^/]+")
                    || path.equals("/api/public/shops")
                    || path.matches("/api/public/shops/[^/]+"));
    }
}
