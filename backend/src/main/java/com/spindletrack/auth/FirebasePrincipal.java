package com.spindletrack.auth;

public record FirebasePrincipal(
        String uid,
        String email,
        String displayName,
        Long userId,
        String role,
        Long shopId,
        String shopStatus) {
}
