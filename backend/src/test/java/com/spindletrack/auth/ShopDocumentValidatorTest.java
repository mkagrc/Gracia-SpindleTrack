package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ShopDocumentValidatorTest {
    private final ShopDocumentValidator validator = new ShopDocumentValidator();

    @Test
    void detectsPngByContentEvenWhenClientMimeAndExtensionLie() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        var result = validator.validate(new MockMultipartFile(
                "documents", "C:\\fakepath\\permit.pdf", "application/pdf", png));

        assertEquals(ShopDocumentType.PNG, result.type());
        assertEquals("permit.pdf", result.originalFilename());
        assertEquals("image/png", result.type().contentType());
    }

    @Test
    void acceptsJpegAndPdfSignatures() {
        var jpeg = validator.validate(new MockMultipartFile(
                "documents", "photo.jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}));
        var pdf = validator.validate(new MockMultipartFile(
                "documents", "permit.pdf", "application/pdf", "%PDF-1.7".getBytes()));

        assertEquals(ShopDocumentType.JPEG, jpeg.type());
        assertEquals(ShopDocumentType.PDF, pdf.type());
    }

    @Test
    void rejectsUnsupportedContentEvenIfTheFilenameLooksAllowed() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> validator.validate(
                new MockMultipartFile("documents", "permit.pdf", "application/pdf", "not a pdf".getBytes())));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsFilesLargerThanFiveMebibytes() {
        byte[] oversized = new byte[(int) ShopDocumentValidator.MAX_FILE_SIZE_BYTES + 1];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> validator.validate(
                new MockMultipartFile("documents", "large.jpg", "image/jpeg", oversized)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsEmptyFiles() {
        assertThrows(ResponseStatusException.class, () -> validator.validate(
                new MockMultipartFile("documents", "empty.png", "image/png", new byte[0])));
    }
}
