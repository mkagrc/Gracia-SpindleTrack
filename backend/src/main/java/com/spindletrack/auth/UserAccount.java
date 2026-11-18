package com.spindletrack.auth;

public record UserAccount(
        long id,
        String firebaseUid,
        String fullName,
        String email,
        String role,
        boolean active,
        Long shopId,
        String shopStatus) {
}
