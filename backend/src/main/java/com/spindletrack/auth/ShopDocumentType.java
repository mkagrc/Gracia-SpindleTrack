package com.spindletrack.auth;

public enum ShopDocumentType {
    JPEG("image/jpeg", ".jpg"),
    PNG("image/png", ".png"),
    PDF("application/pdf", ".pdf");

    private final String contentType;
    private final String extension;

    ShopDocumentType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }
}
