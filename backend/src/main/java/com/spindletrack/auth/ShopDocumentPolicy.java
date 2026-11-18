package com.spindletrack.auth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ShopDocumentPolicy {
    public static final int MAX_FILES_PER_REGISTRATION = 5;
    private final ShopDocumentValidator validator;

    public ShopDocumentPolicy(ShopDocumentValidator validator) {
        this.validator = validator;
    }

    public List<ValidatedShopDocument> validateInitial(List<MultipartFile> uploads) {
        List<MultipartFile> files = normalize(uploads);
        requireCount(0, files.size());
        if (files.isEmpty()) {
            throw badRequest("Upload at least one verification document to register your shop.");
        }
        return validateFiles(files);
    }

    public List<ValidatedShopDocument> validateResubmission(
            String shopStatus,
            int existingDocumentCount,
            List<MultipartFile> uploads) {
        if (!"REJECTED".equals(shopStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Only a rejected shop registration can be edited and resubmitted.");
        }
        List<MultipartFile> files = normalize(uploads);
        requireCount(existingDocumentCount, files.size());
        if (existingDocumentCount + files.size() < 1) {
            throw badRequest("A resubmission must include at least one verification document.");
        }
        return validateFiles(files);
    }

    public void requireRejectedForDocumentEdit(String shopStatus) {
        if (!"REJECTED".equals(shopStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Documents can only be changed for a rejected shop registration.");
        }
    }

    private static void requireCount(int existingCount, int newCount) {
        if (existingCount < 0 || newCount < 0 || existingCount + newCount > MAX_FILES_PER_REGISTRATION) {
            throw badRequest("A shop registration can have no more than five verification documents.");
        }
    }

    private List<ValidatedShopDocument> validateFiles(List<MultipartFile> files) {
        List<ValidatedShopDocument> validated = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            validated.add(validator.validate(file));
        }
        return List.copyOf(validated);
    }

    private static List<MultipartFile> normalize(List<MultipartFile> uploads) {
        return uploads == null ? List.of() : uploads;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
