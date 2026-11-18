package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ShopDocumentPolicyTest {
    private final ShopDocumentPolicy policy = new ShopDocumentPolicy(new ShopDocumentValidator());

    @Test
    void initialRegistrationRequiresAtLeastOneDocument() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> policy.validateInitial(List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void initialRegistrationAllowsAtMostFiveValidatedDocuments() {
        assertEquals(5, policy.validateInitial(files(5)).size());
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> policy.validateInitial(files(6)));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectedShopCanResubmitUsingExistingDocumentsOrAdditionalUploads() {
        assertEquals(0, policy.validateResubmission("REJECTED", 1, List.of()).size());
        assertEquals(1, policy.validateResubmission("REJECTED", 4, files(1)).size());
    }

    @Test
    void resubmissionMustRetainAtLeastOneDocumentAndCannotExceedFive() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> policy.validateResubmission("REJECTED", 0, List.of())).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> policy.validateResubmission("REJECTED", 5, files(1))).getStatusCode());
    }

    @Test
    void onlyRejectedRegistrationsCanBeResubmittedOrHaveDocumentsEdited() {
        for (String status : List.of("PENDING", "ACTIVE", "DEACTIVATED")) {
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> policy.validateResubmission(status, 1, List.of())).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> policy.requireRejectedForDocumentEdit(status)).getStatusCode());
        }
    }

    private static List<MultipartFile> files(int count) {
        byte[] pdf = "%PDF-1.7".getBytes();
        return java.util.stream.IntStream.range(0, count)
                .<MultipartFile>mapToObj(index -> new MockMultipartFile(
                        "documents", "document-" + index + ".pdf", "application/pdf", pdf))
                .toList();
    }
}
