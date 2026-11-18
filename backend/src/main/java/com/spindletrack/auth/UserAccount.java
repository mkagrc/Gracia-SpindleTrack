package com.spindletrack.auth;

public record UserAccount(
        String id,
        String firebaseUid,
        String fullName,
        String email,
        String role,
        boolean active,
        String shopId,
        String shopStatus) {
}
