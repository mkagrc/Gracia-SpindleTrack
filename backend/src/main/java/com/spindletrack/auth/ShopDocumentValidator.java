package com.spindletrack.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ShopDocumentValidator {
    public static final long MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    public ValidatedShopDocument validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Each document must contain a file.");
        }
        long size = file.getSize();
        if (size > MAX_FILE_SIZE_BYTES) {
            throw badRequest("Each verification document must be 5 MB or smaller.");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] signature = input.readNBytes(PNG_SIGNATURE.length);
            ShopDocumentType type = detect(signature);
            if (type == null) {
                throw badRequest("Only JPG, PNG, or PDF documents are accepted; file content did not match.");
            }
            return new ValidatedShopDocument(file, type, safeDisplayName(file.getOriginalFilename()), size);
        } catch (IOException exception) {
            throw badRequest("The uploaded document could not be read.");
        }
    }

    private static ShopDocumentType detect(byte[] signature) {
        if (signature.length >= PNG_SIGNATURE.length
                && Arrays.equals(Arrays.copyOf(signature, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
            return ShopDocumentType.PNG;
        }
        if (signature.length >= 3
                && (signature[0] & 0xff) == 0xff
                && (signature[1] & 0xff) == 0xd8
                && (signature[2] & 0xff) == 0xff) {
            return ShopDocumentType.JPEG;
        }
        if (signature.length >= 5
                && "%PDF-".equals(new String(signature, 0, 5, StandardCharsets.US_ASCII))) {
            return ShopDocumentType.PDF;
        }
        return null;
    }

    static String safeDisplayName(String suppliedName) {
        if (suppliedName == null || suppliedName.isBlank()) {
            return "document";
        }
        String normalized = suppliedName.replace('\\', '/');
        int finalSlash = normalized.lastIndexOf('/');
        String basename = normalized.substring(finalSlash + 1)
                .replaceAll("[\\p{Cntrl}]", "_")
                .strip();
        if (basename.isBlank()) {
            return "document";
        }
        return basename.length() <= 255 ? basename : basename.substring(basename.length() - 255);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
