package com.spindletrack.auth;

import java.time.LocalDateTime;
import java.util.List;

public record ShopRegistrationStatusResponse(
        String shopId,
        String shopName,
        String address,
        String contactNumber,
        String status,
        String rejectionReason,
        LocalDateTime registeredAt,
        List<ShopDocumentMetadata> documents) {
}
