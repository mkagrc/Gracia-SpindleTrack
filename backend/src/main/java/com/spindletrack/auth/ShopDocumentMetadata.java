package com.spindletrack.auth;

import java.time.LocalDateTime;

public record ShopDocumentMetadata(
        String id,
        String documentType,
        String originalFilename,
        String contentType,
        long sizeBytes,
        LocalDateTime uploadedAt) {
}
