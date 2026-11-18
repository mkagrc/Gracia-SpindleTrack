package com.spindletrack.auth;

public record AuthSessionResponse(
        long userId,
        String fullName,
        String email,
        String role,
        Long shopId,
        String shopStatus) {
}
