package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.Test;

class VerifiedCustomerAccountLookupTest {
    @Test
    void matchesCaseInsensitiveNormalizedEmailOnlyForVerifiedCustomer() throws Exception {
        UserAccountRepository repository = repository("customer@example.com", true, "CUSTOMER");
        Optional<UserAccount> result = repository.findVerifiedCustomerByEmail("  Customer@Example.com  ");
        assertTrue(result.isPresent());
        assertEquals("Customer Name", result.orElseThrow().fullName());
    }

    @Test
    void rejectsNonmatchingPartialUnverifiedAndNonCustomerAccounts() throws Exception {
        assertTrue(repository("customer@example.com", true, "CUSTOMER")
                .findVerifiedCustomerByEmail("customer+other@example.com").isEmpty());
        assertTrue(repository("customer@example.com", true, "CUSTOMER")
                .findVerifiedCustomerByEmail("customer").isEmpty());
        assertTrue(repository("customer@example.com", false, "CUSTOMER")
                .findVerifiedCustomerByEmail("customer@example.com").isEmpty());
        assertTrue(repository("customer@example.com", true, "STAFF")
                .findVerifiedCustomerByEmail("customer@example.com").isEmpty());
        assertTrue(repository("customer@example.com", true, "OWNER")
                .findVerifiedCustomerByEmail("customer@example.com").isEmpty());
    }

    private static UserAccountRepository repository(String storedEmail, boolean verified, String role) throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference lookups = mock(CollectionReference.class);
        CollectionReference users = mock(CollectionReference.class);
        DocumentReference lookup = mock(DocumentReference.class);
        DocumentReference userRef = mock(DocumentReference.class);
        DocumentSnapshot pointer = mock(DocumentSnapshot.class);
        DocumentSnapshot user = mock(DocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("userIdentityLookups")).thenReturn(lookups);
        when(db.collection("users")).thenReturn(users);
        when(lookups.document(org.mockito.ArgumentMatchers.anyString())).thenReturn(lookup);
        when(lookup.get()).thenReturn(ApiFutures.immediateFuture(pointer));
        when(pointer.exists()).thenReturn(true);
        when(pointer.getString("userId")).thenReturn("user-string-1");
        when(users.document("user-string-1")).thenReturn(userRef);
        when(userRef.get()).thenReturn(ApiFutures.immediateFuture(user));
        when(user.exists()).thenReturn(true);
        when(user.getId()).thenReturn("user-string-1");
        when(user.getString("firebaseUid")).thenReturn("firebase-uid-1");
        when(user.getString("displayName")).thenReturn("Customer Name");
        when(user.getString("email")).thenReturn(storedEmail);
        when(user.getString("normalizedEmail")).thenReturn(storedEmail);
        when(user.getString("role")).thenReturn(role);
        when(user.getBoolean("active")).thenReturn(true);
        when(user.getBoolean("emailVerified")).thenReturn(verified);
        return new UserAccountRepository(firebase);
    }
}
