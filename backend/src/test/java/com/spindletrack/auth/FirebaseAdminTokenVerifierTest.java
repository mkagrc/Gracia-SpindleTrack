package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.google.api.core.ApiFutures;
import com.google.api.core.ApiFuture;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FirebaseAdminTokenVerifierTest {
    @Test
    void verifiedFirebaseEmailProducesIdentity() {
        FirebaseIdentity identity = FirebaseAdminTokenVerifier.identityFromClaims("uid-1", Map.of(
                "email", "owner@example.com",
                "email_verified", true,
                "name", "Shop Owner"));

        assertEquals("uid-1", identity.uid());
        assertEquals("owner@example.com", identity.email());
        assertEquals("Shop Owner", identity.displayName());
    }

    @Test
    void unverifiedFirebaseEmailIsRejected() {
        FirebaseAuthenticationException exception = assertThrows(FirebaseAuthenticationException.class,
                () -> FirebaseAdminTokenVerifier.identityFromClaims("uid-2", Map.of(
                        "email", "owner@example.com",
                        "email_verified", false)));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("Please verify your email address before signing in.", exception.getMessage());
    }

    @Test
    void missingEmailIsRejected() {
        FirebaseAuthenticationException exception = assertThrows(FirebaseAuthenticationException.class,
                () -> FirebaseAdminTokenVerifier.identityFromClaims("uid-3", Map.of("email_verified", true)));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
    }

    @Test
    void ownerPresenceCleanupDeletesEveryOtherRolePathButRetainsOwnersPath() {
        FirebaseAdminTokenVerifier verifier = new FirebaseAdminTokenVerifier("unused", "https://db.example.test");
        FirebaseDatabase database = mock(FirebaseDatabase.class);
        DatabaseReference root = mock(DatabaseReference.class);
        when(database.getReference("presence")).thenReturn(root);
        when(root.child("admin")).thenReturn(mock(DatabaseReference.class));
        when(root.child("customers")).thenReturn(mock(DatabaseReference.class));
        when(root.child("staff")).thenReturn(mock(DatabaseReference.class));
        DatabaseReference admin = root.child("admin");
        DatabaseReference customers = root.child("customers");
        DatabaseReference staff = root.child("staff");
        when(admin.child("uid-42")).thenReturn(admin);
        when(customers.child("uid-42")).thenReturn(customers);
        when(staff.child("uid-42")).thenReturn(staff);
        when(admin.removeValueAsync()).thenReturn(ApiFutures.immediateFuture(null));
        when(customers.removeValueAsync()).thenReturn(ApiFutures.immediateFuture(null));
        when(staff.removeValueAsync()).thenReturn(ApiFutures.immediateFuture(null));
        setRealtimeDatabase(verifier, database);

        verifier.removeOtherRolePresenceNodes("uid-42", "OWNER");

        verify(admin).removeValueAsync();
        verify(customers).removeValueAsync();
        verify(staff).removeValueAsync();
        // The owners group is intentionally never addressed for deletion.
        verify(root, org.mockito.Mockito.never()).child("owners");
    }

    @Test
    void presenceDeletionFailureDoesNotEscapeCleanup() {
        FirebaseAdminTokenVerifier verifier = new FirebaseAdminTokenVerifier("unused", "https://db.example.test");
        FirebaseDatabase database = mock(FirebaseDatabase.class);
        DatabaseReference root = mock(DatabaseReference.class);
        DatabaseReference customers = mock(DatabaseReference.class);
        when(database.getReference("presence")).thenReturn(root);
        when(root.child("admin")).thenReturn(mock(DatabaseReference.class));
        when(root.child("customers")).thenReturn(customers);
        when(root.child("staff")).thenReturn(mock(DatabaseReference.class));
        when(root.child("owners")).thenReturn(mock(DatabaseReference.class));
        when(customers.child("uid-43")).thenReturn(customers);
        ApiFuture<Void> failed = ApiFutures.immediateFailedFuture(new IllegalStateException("injected failure"));
        when(customers.removeValueAsync()).thenReturn(failed);
        setRealtimeDatabase(verifier, database);

        assertDoesNotThrow(() -> verifier.removeOtherRolePresenceNodes("uid-43", "CUSTOMER"));
    }

    private static void setRealtimeDatabase(FirebaseAdminTokenVerifier verifier, FirebaseDatabase database) {
        try {
            var field = FirebaseAdminTokenVerifier.class.getDeclaredField("firebaseDatabase");
            field.setAccessible(true);
            field.set(verifier, database);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
