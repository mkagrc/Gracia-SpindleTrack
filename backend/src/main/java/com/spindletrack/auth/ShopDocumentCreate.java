package com.spindletrack.auth;

public record ShopDocumentCreate(
        String documentType,
        String originalFilename,
        String storedFilename,
        String contentType,
        long sizeBytes,
        String uploadedBy) {
}
