package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StaffManagementServiceTest {
    @Test
    void invitationStoresShopEmailHashAndSevenDayExpiryButReturnsOnlyTheRawLink() throws Exception {
        Fixture f = fixture();
        when(f.invitationCollection.document()).thenReturn(f.invitationRef);
        when(f.invitationRef.getId()).thenReturn("invite-id-89");
        when(f.shopSnapshot.exists()).thenReturn(true);
        when(f.shopSnapshot.getString("status")).thenReturn("ACTIVE");
        when(f.transaction.get(f.shopRef)).thenReturn(ApiFutures.immediateFuture(f.shopSnapshot));
        runTransaction(f.db, f.transaction);

        StaffManagementService.InvitationLink result = service(f).invite("shop-string-A", "owner-user-1", "  Staff@Example.com ");

        assertEquals("invite-id-89", result.invitation().id());
        assertEquals("staff@example.com", result.invitation().email());
        assertEquals("PENDING", result.invitation().status());
        String rawToken = result.inviteLink().substring(result.inviteLink().indexOf("?token=") + 7);
        assertNotEquals(rawToken, StaffInvitationToken.hash(rawToken));
        ArgumentCaptor<Map<String, Object>> stored = ArgumentCaptor.forClass(Map.class);
        verify(f.transaction).create(eq(f.invitationRef), stored.capture());
        Map<String, Object> fields = stored.getValue();
        assertEquals("shop-string-A", fields.get("shopId"));
        assertEquals("staff@example.com", fields.get("normalizedEmail"));
        assertEquals("PENDING", fields.get("status"));
        assertEquals(StaffInvitationToken.hash(rawToken), fields.get("tokenHash"));
        assertNotEquals(rawToken, fields.get("tokenHash"));
        assertEquals("owner-user-1", fields.get("invitedBy"));
        Instant expiry = ((Timestamp) fields.get("expiresAt")).toDate().toInstant();
        long remainingHours = java.time.Duration.between(Instant.now(), expiry).toHours();
        assertTrue(remainingHours >= 167 && remainingHours <= 168, "Invitation must expire seven days after creation.");
    }

    @Test
    void acceptingValidInvitationUpdatesUserAndInvitationInOneTransactionThenRefreshesClaims() throws Exception {
        Fixture f = acceptanceFixture("PENDING", "staff@example.com", "CUSTOMER", null,
                Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));

        StaffManagementService.AcceptResult result = service(f).accept("raw-invitation-token",
                principal("staff@example.com", "CUSTOMER"));

        assertEquals("STAFF", result.role());
        assertEquals("shop-string-A", result.shopId());
        assertEquals("Active Laundry", result.shopName());
        verify(f.transaction).update(eq(f.userRef), any(Map.class));
        verify(f.transaction).update(eq(f.invitationRef), any(Map.class));
        verify(f.firebase).setSessionClaims("firebase-uid-1", "STAFF", "shop-string-A");
    }

    @Test
    void inboxReturnsOnlyMatchingPendingNonExpiredInvitationWithShopName() throws Exception {
        Fixture f = fixture();
        Query emailQuery = mock(Query.class);
        QuerySnapshot rows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot matching = mock(QueryDocumentSnapshot.class);
        QueryDocumentSnapshot declined = mock(QueryDocumentSnapshot.class);
        QueryDocumentSnapshot expired = mock(QueryDocumentSnapshot.class);
        QueryDocumentSnapshot wrongEmail = mock(QueryDocumentSnapshot.class);
        Timestamp expiry = Timestamp.of(Date.from(Instant.now().plusSeconds(3600)));
        when(f.invitationCollection.whereEqualTo("normalizedEmail", "staff@example.com")).thenReturn(emailQuery);
        when(emailQuery.get()).thenReturn(ApiFutures.immediateFuture(rows));
        when(rows.getDocuments()).thenReturn(List.of(matching, declined, expired, wrongEmail));
        when(matching.getId()).thenReturn("invite-string-A");
        when(matching.getString("normalizedEmail")).thenReturn("staff@example.com");
        when(matching.getString("status")).thenReturn("PENDING");
        when(matching.getString("shopId")).thenReturn("shop-string-A");
        when(matching.get("createdAt")).thenReturn(Timestamp.of(Date.from(Instant.now().minusSeconds(60))));
        when(matching.get("expiresAt")).thenReturn(expiry);
        when(declined.getString("normalizedEmail")).thenReturn("staff@example.com");
        when(declined.getString("status")).thenReturn("DECLINED");
        when(expired.getString("normalizedEmail")).thenReturn("staff@example.com");
        when(expired.getString("status")).thenReturn("PENDING");
        when(expired.get("expiresAt")).thenReturn(Timestamp.of(Date.from(Instant.now().minusSeconds(10))));
        when(wrongEmail.getString("normalizedEmail")).thenReturn("other@example.com");
        when(wrongEmail.getString("status")).thenReturn("PENDING");
        when(wrongEmail.get("expiresAt")).thenReturn(expiry);
        when(f.shopRef.get()).thenReturn(ApiFutures.immediateFuture(f.shopSnapshot));
        when(f.shopSnapshot.exists()).thenReturn(true);
        when(f.shopSnapshot.getString("name")).thenReturn("Active Laundry");

        List<StaffManagementService.InboxInvitation> inbox = service(f).invitationsForUser(principal("STAFF@EXAMPLE.COM", "CUSTOMER"));

        assertEquals(1, inbox.size());
        assertEquals("invite-string-A", inbox.get(0).invitationId());
        assertEquals("Active Laundry", inbox.get(0).shopName());
        assertEquals(expiry.toDate().toInstant(), inbox.get(0).expiresAt());
        verify(f.invitationCollection).whereEqualTo("normalizedEmail", "staff@example.com");
    }

    @Test
    void inboxInvitationIdCanBePassedToAcceptById() throws Exception {
        Fixture f = acceptanceFixture("PENDING", "staff@example.com", "CUSTOMER", null,
                Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));
        Query inboxQuery = mock(Query.class);
        QuerySnapshot rows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot invitation = mock(QueryDocumentSnapshot.class);
        when(f.invitationCollection.whereEqualTo("normalizedEmail", "staff@example.com")).thenReturn(inboxQuery);
        when(inboxQuery.get()).thenReturn(ApiFutures.immediateFuture(rows));
        when(rows.getDocuments()).thenReturn(List.of(invitation));
        when(invitation.getId()).thenReturn("invite-string-A");
        when(invitation.exists()).thenReturn(true);
        when(invitation.getReference()).thenReturn(f.invitationRef);
        when(invitation.getString("status")).thenReturn("PENDING", "PENDING");
        when(invitation.getString("normalizedEmail")).thenReturn("staff@example.com");
        when(invitation.getString("shopId")).thenReturn("shop-string-A");
        when(invitation.get("createdAt")).thenReturn(Timestamp.of(Date.from(Instant.now().minusSeconds(30))));
        when(invitation.get("expiresAt")).thenReturn(Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));
        when(f.shopRef.get()).thenReturn(ApiFutures.immediateFuture(f.shopSnapshot));
        when(f.shopSnapshot.exists()).thenReturn(true);
        when(f.shopSnapshot.getString("name")).thenReturn("Active Laundry");
        when(f.invitationCollection.document("invite-string-A")).thenReturn(f.invitationRef);
        when(f.transaction.get(f.invitationRef)).thenReturn(ApiFutures.immediateFuture(invitation));
        StaffManagementService service = service(f);

        List<StaffManagementService.InboxInvitation> inbox = service.invitationsForUser(principal("staff@example.com", "CUSTOMER"));
        StaffManagementService.AcceptResult accepted = service.acceptById(inbox.get(0).invitationId(), principal("staff@example.com", "CUSTOMER"));

        assertEquals("invite-string-A", inbox.get(0).invitationId());
        assertEquals("STAFF", accepted.role());
        assertEquals("shop-string-A", accepted.shopId());
        verify(f.invitationCollection).document("invite-string-A");
        verify(f.firebase).setSessionClaims("firebase-uid-1", "STAFF", "shop-string-A");
    }

    @Test
    void otherOrUnverifiedEmailGetsNoInboxInvitations() {
        Fixture f = fixture();
        Query differentEmailQuery = mock(Query.class);
        QuerySnapshot noRows = mock(QuerySnapshot.class);
        when(f.invitationCollection.whereEqualTo("normalizedEmail", "other@example.com")).thenReturn(differentEmailQuery);
        when(differentEmailQuery.get()).thenReturn(ApiFutures.immediateFuture(noRows));
        when(noRows.getDocuments()).thenReturn(List.of());
        StaffManagementService service = service(f);
        assertEquals(List.of(), service.invitationsForUser(principal("other@example.com", "CUSTOMER")));
        verify(f.invitationCollection).whereEqualTo("normalizedEmail", "other@example.com");

        FirebasePrincipal unverified = new FirebasePrincipal("firebase-uid-1", "staff@example.com", "Staff",
                "firestore-user-1", "CUSTOMER", null, null, false);
        Fixture unverifiedFixture = fixture();
        assertEquals(List.of(), service(unverifiedFixture).invitationsForUser(unverified));
        verify(unverifiedFixture.firebase, never()).firestore();

        for (String role : List.of("STAFF", "OWNER", "PLATFORM_ADMIN")) {
            Fixture roleFixture = fixture();
            FirebasePrincipal nonCustomer = principal("staff@example.com", role);
            assertEquals(List.of(), service(roleFixture).invitationsForUser(nonCustomer));
            verify(roleFixture.firebase, never()).firestore();
        }
    }

    @Test
    void acceptingByInvitationIdCreatesStaffOnceAndRejectsReuse() throws Exception {
        Fixture f = acceptanceFixture("PENDING", "staff@example.com", "CUSTOMER", null,
                Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));
        QueryDocumentSnapshot invitation = mock(QueryDocumentSnapshot.class);
        when(invitation.exists()).thenReturn(true);
        when(invitation.getReference()).thenReturn(f.invitationRef);
        when(invitation.getString("status")).thenReturn("PENDING", "ACCEPTED");
        when(invitation.getString("normalizedEmail")).thenReturn("staff@example.com");
        when(invitation.getString("shopId")).thenReturn("shop-string-A");
        when(invitation.get("expiresAt")).thenReturn(Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));
        when(f.invitationCollection.document("invite-string-A")).thenReturn(f.invitationRef);
        when(f.transaction.get(f.invitationRef)).thenReturn(ApiFutures.immediateFuture(invitation));
        StaffManagementService service = service(f);

        assertEquals("STAFF", service.acceptById("invite-string-A", principal("staff@example.com", "CUSTOMER")).role());
        ResponseStatusException reused = assertThrows(ResponseStatusException.class,
                () -> service.acceptById("invite-string-A", principal("staff@example.com", "CUSTOMER")));

        assertEquals(HttpStatus.CONFLICT, reused.getStatusCode());
        verify(f.transaction, times(1)).update(eq(f.userRef), any(Map.class));
        verify(f.firebase, times(1)).setSessionClaims("firebase-uid-1", "STAFF", "shop-string-A");
    }

    @Test
    void wrongUserGetsNotFoundAndOwnerCannotAcceptById() throws Exception {
        Fixture wrong = acceptanceFixture("PENDING", "invited@example.com", "CUSTOMER", null,
                Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));
        QueryDocumentSnapshot wrongInvite = mock(QueryDocumentSnapshot.class);
        when(wrong.invitationCollection.document("invite-string-A")).thenReturn(wrong.invitationRef);
        when(wrong.transaction.get(wrong.invitationRef)).thenReturn(ApiFutures.immediateFuture(wrongInvite));
        when(wrongInvite.exists()).thenReturn(true);
        when(wrongInvite.getString("normalizedEmail")).thenReturn("invited@example.com");
        ResponseStatusException hidden = assertThrows(ResponseStatusException.class, () ->
                service(wrong).acceptById("invite-string-A", principal("other@example.com", "CUSTOMER")));
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());

        Fixture owner = acceptanceFixture("PENDING", "staff@example.com", "OWNER", null,
                Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));
        QueryDocumentSnapshot ownerInvite = mock(QueryDocumentSnapshot.class);
        when(owner.invitationCollection.document("invite-string-A")).thenReturn(owner.invitationRef);
        when(owner.transaction.get(owner.invitationRef)).thenReturn(ApiFutures.immediateFuture(ownerInvite));
        when(ownerInvite.exists()).thenReturn(true);
        when(ownerInvite.getReference()).thenReturn(owner.invitationRef);
        when(ownerInvite.getString("normalizedEmail")).thenReturn("staff@example.com");
        when(ownerInvite.getString("status")).thenReturn("PENDING");
        when(ownerInvite.getString("shopId")).thenReturn("shop-string-A");
        when(ownerInvite.get("expiresAt")).thenReturn(Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class, () ->
                service(owner).acceptById("invite-string-A", principal("staff@example.com", "OWNER")));
        assertEquals(HttpStatus.CONFLICT, forbidden.getStatusCode());
        verify(owner.transaction, never()).update(any(DocumentReference.class), any(Map.class));
    }

    @Test
    void matchingUserCanDeclineInvitation() throws Exception {
        Fixture f = fixture();
        DocumentSnapshot invitation = mock(DocumentSnapshot.class);
        when(f.invitationCollection.document("invite-string-A")).thenReturn(f.invitationRef);
        when(f.transaction.get(f.invitationRef)).thenReturn(ApiFutures.immediateFuture(invitation));
        when(invitation.exists()).thenReturn(true);
        when(invitation.getString("normalizedEmail")).thenReturn("staff@example.com");
        when(invitation.getString("status")).thenReturn("PENDING");
        when(invitation.get("expiresAt")).thenReturn(Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));
        runTransaction(f.db, f.transaction);

        assertEquals("DECLINED", service(f).decline("invite-string-A", principal("staff@example.com", "CUSTOMER")).status());
        verify(f.transaction).update(eq(f.invitationRef), org.mockito.ArgumentMatchers.argThat(fields -> "DECLINED".equals(fields.get("status"))));
    }

    @Test
    void expiredInvitationIsPersistedAsExpiredAndRejected() throws Exception {
        Fixture f = acceptanceFixture("PENDING", "staff@example.com", "CUSTOMER", null,
                Timestamp.of(Date.from(Instant.now().minusSeconds(5))));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service(f).accept("raw-invitation-token", principal("staff@example.com", "CUSTOMER")));

        assertEquals(HttpStatus.GONE, error.getStatusCode());
        assertEquals("This invitation has expired. Ask the shop owner to resend it.", error.getReason());
        ArgumentCaptor<Map<String, Object>> update = ArgumentCaptor.forClass(Map.class);
        verify(f.transaction).update(eq(f.invitationRef), update.capture());
        assertEquals("EXPIRED", update.getValue().get("status"));
        verify(f.transaction, never()).update(eq(f.userRef), any(Map.class));
    }

    @Test
    void wrongVerifiedEmailIsRejectedWithoutChangingEitherDocument() throws Exception {
        Fixture f = acceptanceFixture("PENDING", "invited@example.com", "CUSTOMER", null,
                Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service(f).accept("raw-invitation-token", principal("other@example.com", "CUSTOMER")));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(f.transaction, never()).update(any(DocumentReference.class), any(Map.class));
        verify(f.firebase, never()).setSessionClaims(any(), any(), any());
    }

    @Test
    void acceptedTokenCannotBeReused() throws Exception {
        Fixture f = acceptanceFixture("ACCEPTED", "staff@example.com", "CUSTOMER", null,
                Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service(f).accept("raw-invitation-token", principal("staff@example.com", "CUSTOMER")));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("This invitation has already been used.", error.getReason());
        verify(f.transaction, never()).update(any(DocumentReference.class), any(Map.class));
        verify(f.firebase, never()).setSessionClaims(any(), any(), any());
    }

    @Test
    void staffOrAssignedCustomerCannotJoinASecondShop() throws Exception {
        Fixture f = acceptanceFixture("PENDING", "staff@example.com", "STAFF", "shop-string-B",
                Timestamp.of(Date.from(Instant.now().plusSeconds(3600))));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service(f).accept("raw-invitation-token", principal("staff@example.com", "STAFF")));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("This account cannot join another shop. Only an unassigned customer can accept a staff invitation.", error.getReason());
        verify(f.transaction, never()).update(any(DocumentReference.class), any(Map.class));
    }

    @Test
    void ownerCannotRevokeInvitationFromAnotherShop() throws Exception {
        Fixture f = fixture();
        DocumentSnapshot otherShopInvitation = mock(DocumentSnapshot.class);
        when(f.invitationCollection.document("invite-id-other-shop")).thenReturn(f.invitationRef);
        when(f.shopSnapshot.exists()).thenReturn(true);
        when(f.shopSnapshot.getString("status")).thenReturn("ACTIVE");
        when(otherShopInvitation.exists()).thenReturn(true);
        when(otherShopInvitation.getString("shopId")).thenReturn("shop-string-B");
        when(f.transaction.get(f.shopRef)).thenReturn(ApiFutures.immediateFuture(f.shopSnapshot));
        when(f.transaction.get(f.invitationRef)).thenReturn(ApiFutures.immediateFuture(otherShopInvitation));
        runTransaction(f.db, f.transaction);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service(f).revoke("shop-string-A", "invite-id-other-shop"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(f.transaction, never()).update(any(DocumentReference.class), any(Map.class));
    }

    @Test
    void ownerCannotRemoveStaffFromAnotherShop() throws Exception {
        Fixture f = fixture();
        CollectionReference users = mock(CollectionReference.class);
        DocumentReference staffRef = mock(DocumentReference.class);
        DocumentSnapshot staffSnapshot = mock(DocumentSnapshot.class);
        when(f.db.collection("users")).thenReturn(users);
        when(users.document("staff-string-id")).thenReturn(staffRef);
        when(f.shopSnapshot.exists()).thenReturn(true);
        when(f.shopSnapshot.getString("status")).thenReturn("ACTIVE");
        when(staffSnapshot.exists()).thenReturn(true);
        when(staffSnapshot.getString("role")).thenReturn("STAFF");
        when(staffSnapshot.getString("shopId")).thenReturn("shop-string-B");
        when(f.transaction.get(f.shopRef)).thenReturn(ApiFutures.immediateFuture(f.shopSnapshot));
        when(f.transaction.get(staffRef)).thenReturn(ApiFutures.immediateFuture(staffSnapshot));
        runTransaction(f.db, f.transaction);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service(f).removeStaff("shop-string-A", "staff-string-id"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(f.transaction, never()).update(any(DocumentReference.class), any(Map.class));
        verify(f.firebase, never()).setSessionClaims(any(), any(), any());
    }

    @Test
    void removingOwnStaffChangesRoleAndRefreshesClaimsWithoutDeletingTheUser() throws Exception {
        Fixture f = fixture();
        CollectionReference users = mock(CollectionReference.class);
        DocumentReference staffRef = mock(DocumentReference.class);
        DocumentSnapshot staffSnapshot = mock(DocumentSnapshot.class);
        when(f.db.collection("users")).thenReturn(users);
        when(users.document("staff-string-id")).thenReturn(staffRef);
        when(f.shopSnapshot.exists()).thenReturn(true);
        when(f.shopSnapshot.getString("status")).thenReturn("ACTIVE");
        when(staffSnapshot.exists()).thenReturn(true);
        when(staffSnapshot.getString("role")).thenReturn("STAFF");
        when(staffSnapshot.getString("shopId")).thenReturn("shop-string-A");
        when(staffSnapshot.getString("firebaseUid")).thenReturn("staff-firebase-uid");
        when(staffSnapshot.getString("displayName")).thenReturn("Shop Staff");
        when(staffSnapshot.getString("email")).thenReturn("staff@example.com");
        when(staffSnapshot.getBoolean("active")).thenReturn(true);
        when(f.transaction.get(f.shopRef)).thenReturn(ApiFutures.immediateFuture(f.shopSnapshot));
        when(f.transaction.get(staffRef)).thenReturn(ApiFutures.immediateFuture(staffSnapshot));
        runTransaction(f.db, f.transaction);

        service(f).removeStaff("shop-string-A", "staff-string-id");

        ArgumentCaptor<Map<String, Object>> changed = ArgumentCaptor.forClass(Map.class);
        verify(f.transaction).update(eq(staffRef), changed.capture());
        assertEquals("CUSTOMER", changed.getValue().get("role"));
        verify(f.firebase).setSessionClaims("staff-firebase-uid", "CUSTOMER", null);
    }

    private static Fixture acceptanceFixture(String invitationStatus, String invitedEmail, String userRole,
            String userShop, Timestamp expiresAt) throws Exception {
        Fixture f = fixture();
        Query tokenQuery = mock(Query.class);
        QuerySnapshot tokenRows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot invitation = mock(QueryDocumentSnapshot.class);
        DocumentReference userRef = mock(DocumentReference.class);
        DocumentSnapshot userSnapshot = mock(DocumentSnapshot.class);
        CollectionReference users = mock(CollectionReference.class);
        when(f.invitationCollection.whereEqualTo("tokenHash", StaffInvitationToken.hash("raw-invitation-token"))).thenReturn(tokenQuery);
        when(f.transaction.get(tokenQuery)).thenReturn(ApiFutures.immediateFuture(tokenRows));
        when(tokenRows.isEmpty()).thenReturn(false);
        when(tokenRows.getDocuments()).thenReturn(List.of(invitation));
        when(invitation.getReference()).thenReturn(f.invitationRef);
        when(invitation.getString("status")).thenReturn(invitationStatus);
        when(invitation.getString("normalizedEmail")).thenReturn(invitedEmail);
        when(invitation.getString("shopId")).thenReturn("shop-string-A");
        when(invitation.get("expiresAt")).thenReturn(expiresAt);
        when(f.invitationCollection.document("invite-string-A")).thenReturn(f.invitationRef);
        when(f.invitationRef.get()).thenReturn(ApiFutures.immediateFuture(invitation));
        when(f.transaction.get(f.invitationRef)).thenReturn(ApiFutures.immediateFuture(invitation));
        when(f.db.collection("shops")).thenReturn(f.shopCollection);
        when(f.shopCollection.document("shop-string-A")).thenReturn(f.shopRef);
        when(f.shopSnapshot.exists()).thenReturn(true);
        when(f.shopSnapshot.getString("status")).thenReturn("ACTIVE");
        when(f.shopSnapshot.getString("name")).thenReturn("Active Laundry");
        when(f.db.collection("users")).thenReturn(users);
        when(users.document("firestore-user-1")).thenReturn(userRef);
        when(userSnapshot.exists()).thenReturn(true);
        when(userSnapshot.getBoolean("active")).thenReturn(true);
        when(userSnapshot.getString("normalizedEmail")).thenReturn("staff@example.com");
        when(userSnapshot.getString("role")).thenReturn(userRole);
        when(userSnapshot.getString("shopId")).thenReturn(userShop);
        when(f.transaction.get(f.shopRef)).thenReturn(ApiFutures.immediateFuture(f.shopSnapshot));
        when(f.transaction.get(userRef)).thenReturn(ApiFutures.immediateFuture(userSnapshot));
        runTransaction(f.db, f.transaction);
        return new Fixture(f.firebase, f.db, f.shopCollection, f.shopRef, f.shopSnapshot,
                f.invitationCollection, f.invitationRef, f.transaction, userRef);
    }

    private static FirebasePrincipal principal(String email, String role) {
        return new FirebasePrincipal("firebase-uid-1", email, "Staff", "firestore-user-1", role, null, null);
    }

    private static StaffManagementService service(Fixture f) {
        return new StaffManagementService(f.firebase, group -> Map.of(), "https://spindletrack.example");
    }

    private static Fixture fixture() {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference invitations = mock(CollectionReference.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        DocumentReference invitationRef = mock(DocumentReference.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        Transaction transaction = mock(Transaction.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("shops")).thenReturn(shops);
        when(db.collection("staffInvitations")).thenReturn(invitations);
        when(shops.document("shop-string-A")).thenReturn(shopRef);
        return new Fixture(firebase, db, shops, shopRef, shop, invitations, invitationRef, transaction, null);
    }

    @SuppressWarnings("unchecked")
    private static void runTransaction(Firestore db, Transaction transaction) throws Exception {
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(work.updateCallback(transaction)); }
            catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
        });
    }

    private record Fixture(FirebaseAdminTokenVerifier firebase, Firestore db, CollectionReference shopCollection,
            DocumentReference shopRef, DocumentSnapshot shopSnapshot, CollectionReference invitationCollection,
            DocumentReference invitationRef, Transaction transaction, DocumentReference userRef) {}
}
