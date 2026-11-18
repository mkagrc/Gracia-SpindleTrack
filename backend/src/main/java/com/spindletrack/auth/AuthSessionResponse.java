package com.spindletrack.auth;

public record AuthSessionResponse(
        String userId,
        String fullName,
        String email,
        String role,
        String shopId,
        String shopStatus,
        ShopInfo shop) {
    public record ShopInfo(String id, String name, String status) {}
}
