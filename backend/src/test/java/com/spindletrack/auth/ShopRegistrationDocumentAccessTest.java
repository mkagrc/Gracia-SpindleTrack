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
import org.springframework.web.server.ResponseStatusException;

class ShopRegistrationDocumentAccessTest {
    private final ShopDocumentRepository documents = mock(ShopDocumentRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private ShopRegistrationService registrations;

    @BeforeEach
    void setUp() {
        registrations = new ShopRegistrationService(mock(FirebaseAdminTokenVerifier.class),
                mock(UserAccountRepository.class), new ShopRegistrationEligibility(), documents,
                new ShopDocumentPolicy(new ShopDocumentValidator()), storage, new ShopDocumentAccessPolicy());
        when(documents.findById("doc-a")).thenReturn(Optional.of(new ShopDocumentRecord(
                "doc-a", "shop-7", "OTHER", "permit.pdf", "7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf",
                "application/pdf", 8L, "user-41", LocalDateTime.now())));
    }

    @Test
    void uploadingOwnerCanOpenTheirDocument() throws Exception {
        byte[] content = "%PDF-1.7".getBytes();
        when(storage.open("7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf")).thenReturn(new ByteArrayInputStream(content));
        ShopDocumentDownload result = registrations.openDocument("doc-a", "user-41", "OWNER");
        assertArrayEquals(content, result.content().readAllBytes());
    }

    @Test
    void platformAdministratorCanOpenDocument() throws Exception {
        when(storage.open("7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf"))
                .thenReturn(new ByteArrayInputStream("%PDF-1.7".getBytes()));
        registrations.openDocument("doc-a", "admin-900", "PLATFORM_ADMIN");
    }

    @Test
    void differentOwnerAndStaffCannotOpenDocument() {
        assertThrows(ResponseStatusException.class, () -> registrations.openDocument("doc-a", "user-42", "OWNER"));
        assertThrows(ResponseStatusException.class, () -> registrations.openDocument("doc-a", "user-41", "STAFF"));
    }
}
