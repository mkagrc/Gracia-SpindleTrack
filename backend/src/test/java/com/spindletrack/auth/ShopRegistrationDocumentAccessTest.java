package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ShopRegistrationDocumentAccessTest {
    private final ShopDocumentRepository documents = mock(ShopDocumentRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private ShopRegistrationService registrations;

    @BeforeEach
    void setUp() {
        registrations = new ShopRegistrationService(
                mock(JdbcTemplate.class),
                mock(UserAccountRepository.class),
                new ShopRegistrationEligibility(),
                documents,
                new ShopDocumentPolicy(new ShopDocumentValidator()),
                storage,
                new ShopDocumentAccessPolicy());
        when(documents.findById(12L)).thenReturn(Optional.of(new ShopDocumentRecord(
                12L, 7L, "OTHER", "permit.pdf", "7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf",
                "application/pdf", 8L, 41L, LocalDateTime.now())));
    }

    @Test
    void uploadingOwnerCanOpenTheirDocument() throws Exception {
        byte[] content = "%PDF-1.7".getBytes();
        when(storage.open("7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf"))
                .thenReturn(new ByteArrayInputStream(content));

        ShopDocumentDownload result = registrations.openDocument(12L, 41L, "OWNER");

        assertArrayEquals(content, result.content().readAllBytes());
    }

    @Test
    void platformAdministratorCanOpenDocument() throws Exception {
        when(storage.open("7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf"))
                .thenReturn(new ByteArrayInputStream("%PDF-1.7".getBytes()));

        registrations.openDocument(12L, 900L, "PLATFORM_ADMIN");
    }

    @Test
    void differentOwnerAndStaffCannotOpenDocument() {
        assertThrows(ResponseStatusException.class,
                () -> registrations.openDocument(12L, 42L, "OWNER"));
        assertThrows(ResponseStatusException.class,
                () -> registrations.openDocument(12L, 41L, "STAFF"));
    }
}
