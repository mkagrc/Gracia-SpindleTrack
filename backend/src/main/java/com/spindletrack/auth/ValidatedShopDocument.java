package com.spindletrack.auth;

import org.springframework.web.multipart.MultipartFile;

public record ValidatedShopDocument(
        MultipartFile file,
        ShopDocumentType type,
        String originalFilename,
        long sizeBytes) {
}
