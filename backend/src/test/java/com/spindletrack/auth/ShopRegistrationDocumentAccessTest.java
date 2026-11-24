package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopRegistrationDocumentAccessTest {
    private final ShopDocumentRepository documents = mock(ShopDocumentRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private ShopRegistrationService registrations;

    @BeforeEach
    void setUp() {
        registrations = new ShopRegistrationService(mock(FirebaseAdminTokenVerifier.class),
                users, new ShopRegistrationEligibility(), documents,
                new ShopDocumentPolicy(new ShopDocumentValidator()), storage, new ShopDocumentAccessPolicy());
        when(documents.findById("doc-a")).thenReturn(Optional.of(new ShopDocumentRecord(
                "doc-a", "shop-7", "OTHER", "permit.pdf", "7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf",
                "application/pdf", 8L, "user-41", LocalDateTime.now())));
    }

    @Test
    void uploadingOwnerCanOpenTheirDocument() throws Exception {
        when(users.findByFirebaseUid("firebase-owner")).thenReturn(Optional.of(owner("shop-7", "PENDING")));
        byte[] content = "%PDF-1.7".getBytes();
        when(storage.open("7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf")).thenReturn(new ByteArrayInputStream(content));
        ShopDocumentDownload result = registrations.openDocument("doc-a", "firebase-owner", "OWNER");
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
        when(users.findByFirebaseUid("firebase-other")).thenReturn(Optional.of(owner("shop-other", "ACTIVE")));
        assertThrows(ResponseStatusException.class, () -> registrations.openDocument("doc-a", "firebase-other", "OWNER"));
        assertThrows(ResponseStatusException.class, () -> registrations.openDocument("doc-a", "user-41", "STAFF"));
    }

    @Test
    void ownerWhoUploadedDocumentStillCannotOpenItAfterShopMismatch() {
        when(users.findByFirebaseUid("firebase-owner")).thenReturn(Optional.of(owner("shop-other", "ACTIVE")));
        assertThrows(ResponseStatusException.class,
                () -> registrations.openDocument("doc-a", "firebase-owner", "OWNER"));
    }

    @Test
    void rejectedOwnerCanDeleteTheirOwnDocument() throws Exception {
        when(users.findByFirebaseUid("firebase-owner")).thenReturn(Optional.of(owner("shop-7", "REJECTED")));
        when(documents.delete("shop-7", "doc-a", "user-41")).thenReturn(true);

        registrations.deleteRejectedDocument("firebase-owner", "doc-a");

        verify(documents).delete("shop-7", "doc-a", "user-41");
        verify(storage).delete("7a3e1f68-1348-42ae-b2b9-6029d4e6cd8f.pdf");
    }

    @Test
    void ownerCannotDeleteAnotherShopsDocument() {
        when(users.findByFirebaseUid("firebase-owner")).thenReturn(Optional.of(owner("shop-7", "REJECTED")));
        when(documents.findById("doc-a")).thenReturn(Optional.of(new ShopDocumentRecord(
                "doc-a", "shop-other", "OTHER", "permit.pdf", "other.pdf", "application/pdf", 8L,
                "user-41", LocalDateTime.now())));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> registrations.deleteRejectedDocument("firebase-owner", "doc-a"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(documents, never()).delete("shop-7", "doc-a", "user-41");
    }

    @Test
    void pendingAndActiveOwnersCannotDeleteDocuments() {
        for (String status : new String[] { "PENDING", "ACTIVE" }) {
            when(users.findByFirebaseUid("firebase-owner")).thenReturn(Optional.of(owner("shop-7", status)));

            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> registrations.deleteRejectedDocument("firebase-owner", "doc-a"));

            assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
            verify(documents, never()).delete("shop-7", "doc-a", "user-41");
        }
    }

    private static UserAccount owner(String shopId, String status) {
        return new UserAccount("user-41", "firebase-owner", "Shop Owner", "owner@example.test",
                "OWNER", true, shopId, status);
    }
}
