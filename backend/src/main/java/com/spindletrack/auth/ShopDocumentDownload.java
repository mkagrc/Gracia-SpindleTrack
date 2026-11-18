package com.spindletrack.auth;

import java.io.InputStream;

public record ShopDocumentDownload(
        InputStream content,
        String originalFilename,
        String contentType,
        long sizeBytes) {
}
