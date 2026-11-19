package com.spindletrack.auth;

public record FirebasePrincipal(
        String uid,
        String email,
        String displayName,
        String userId,
        String role,
        String shopId,
        String shopStatus,
        boolean emailVerified) {
    public FirebasePrincipal(String uid, String email, String displayName, String userId, String role,
            String shopId, String shopStatus) {
        this(uid, email, displayName, userId, role, shopId, shopStatus, true);
    }
}
