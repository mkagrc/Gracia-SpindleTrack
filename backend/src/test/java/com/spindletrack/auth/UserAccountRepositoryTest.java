package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class UserAccountRepositoryTest {
    @Test
    void adminPromotionRemovesShopLinkAndRefreshesIdentityLookups() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference users = mock(CollectionReference.class);
        CollectionReference lookups = mock(CollectionReference.class);
        DocumentReference userRef = mock(DocumentReference.class);
        DocumentReference uidRef = mock(DocumentReference.class);
        DocumentReference emailRef = mock(DocumentReference.class);
        Transaction tx = mock(Transaction.class);
        DocumentSnapshot user = mock(DocumentSnapshot.class);
        DocumentSnapshot uid = mock(DocumentSnapshot.class);
        DocumentSnapshot email = mock(DocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("users")).thenReturn(users);
        when(db.collection("userIdentityLookups")).thenReturn(lookups);
        when(users.document("user-3")).thenReturn(userRef);
        when(lookups.document(anyString())).thenReturn(uidRef, emailRef);
        when(tx.get(userRef)).thenReturn(ApiFutures.immediateFuture(user));
        when(tx.get(uidRef)).thenReturn(ApiFutures.immediateFuture(uid));
        when(tx.get(emailRef)).thenReturn(ApiFutures.immediateFuture(email));
        when(user.exists()).thenReturn(true);
        when(uid.exists()).thenReturn(false);
        when(email.exists()).thenReturn(false);
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(work.updateCallback(tx)); }
            catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
        });

        new UserAccountRepository(firebase).bindAndPromotePlatformAdmin(
                "user-3", "admin-uid", "Administrator", " ADMIN@Example.com ");

        verify(tx).update(org.mockito.ArgumentMatchers.eq(userRef), argThat(data ->
                "PLATFORM_ADMIN".equals(data.get("role")) && data.containsKey("shopId")
                        && data.get("shopId") != null));
        verify(tx).set(uidRef, java.util.Map.of("userId", "user-3"));
        verify(tx).set(emailRef, java.util.Map.of("userId", "user-3"));
    }

    @Test
    void duplicateFirebaseUidIsRejectedWithinTransaction() throws Exception {
        assertDuplicateIdentity(true, false);
    }

    @Test
    void duplicateNormalizedEmailIsRejectedWithinTransaction() throws Exception {
        assertDuplicateIdentity(false, true);
    }

    private static void assertDuplicateIdentity(boolean uidExists, boolean emailExists) throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference lookups = mock(CollectionReference.class);
        DocumentReference uidRef = mock(DocumentReference.class);
        DocumentReference emailRef = mock(DocumentReference.class);
        Transaction tx = mock(Transaction.class);
        DocumentSnapshot uidSnapshot = mock(DocumentSnapshot.class);
        DocumentSnapshot emailSnapshot = mock(DocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("userIdentityLookups")).thenReturn(lookups);
        when(lookups.document(anyString())).thenReturn(uidRef, emailRef);
        when(tx.get(uidRef)).thenReturn(ApiFutures.immediateFuture(uidSnapshot));
        when(tx.get(emailRef)).thenReturn(ApiFutures.immediateFuture(emailSnapshot));
        when(uidSnapshot.exists()).thenReturn(uidExists);
        when(emailSnapshot.exists()).thenReturn(emailExists);
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(work.updateCallback(tx)); }
            catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
        });

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new UserAccountRepository(firebase).insertFirebaseUser(
                        "firebase-uid", "Person", "  Person@Example.com  ", "CUSTOMER"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }
}
