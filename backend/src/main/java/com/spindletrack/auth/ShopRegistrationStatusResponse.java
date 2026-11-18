package com.spindletrack.auth;

import java.time.LocalDateTime;

public record ShopRegistrationStatusResponse(
        long shopId,
        String shopName,
        String address,
        String contactNumber,
        String status,
        String rejectionReason,
        LocalDateTime registeredAt) {
}
