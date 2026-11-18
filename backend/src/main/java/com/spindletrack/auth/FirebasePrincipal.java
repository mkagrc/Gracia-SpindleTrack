package com.spindletrack.auth;

public record FirebasePrincipal(
        String uid,
        String email,
        String displayName,
        String userId,
        String role,
        String shopId,
        String shopStatus) {
}
