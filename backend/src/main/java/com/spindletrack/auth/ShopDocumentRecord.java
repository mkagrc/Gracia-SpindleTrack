package com.spindletrack.auth;

import java.time.LocalDateTime;

public record ShopDocumentRecord(
        String id,
        String shopId,
        String documentType,
        String originalFilename,
        String storedFilename,
        String contentType,
        long sizeBytes,
        String uploadedBy,
        LocalDateTime uploadedAt) {

    public ShopDocumentMetadata metadata() {
        return new ShopDocumentMetadata(id, documentType, originalFilename, contentType, sizeBytes, uploadedAt);
    }
}
