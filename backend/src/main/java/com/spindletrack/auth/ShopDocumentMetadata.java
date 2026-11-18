package com.spindletrack.auth;

import java.time.LocalDateTime;

public record ShopDocumentMetadata(
        long id,
        String documentType,
        String originalFilename,
        String contentType,
        long sizeBytes,
        LocalDateTime uploadedAt) {
}
