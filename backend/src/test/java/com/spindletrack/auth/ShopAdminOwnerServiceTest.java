package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopAdminOwnerServiceTest {
    @Test
    void rejectFromPendingUsesFirestoreTransactionAndWritesAudit() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference history = mock(CollectionReference.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        DocumentReference historyRef = mock(DocumentReference.class);
        Transaction tx = mock(Transaction.class);
        DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("shops")).thenReturn(shops);
        when(db.collection("shopReviewHistory")).thenReturn(history);
        when(shops.document("shop-2")).thenReturn(shopRef);
        when(history.document()).thenReturn(historyRef);
        when(snapshot.exists()).thenReturn(true);
        when(snapshot.getString("status")).thenReturn("PENDING");
        when(tx.get(shopRef)).thenReturn(ApiFutures.immediateFuture(snapshot));
        runTransaction(db, tx);

        new AdminShopService(firebase, mock(ShopDocumentRepository.class))
                .transition("shop-2", "REJECTED", "Missing permit", "admin-99");

        verify(tx).update(org.mockito.ArgumentMatchers.eq(shopRef), org.mockito.ArgumentMatchers.argThat(data ->
                "REJECTED".equals(data.get("status")) && "Missing permit".equals(data.get("rejectionReason"))));
        verify(tx).create(org.mockito.ArgumentMatchers.eq(historyRef), org.mockito.ArgumentMatchers.argThat(data ->
                "shop-2".equals(data.get("shopId")) && "REJECTED".equals(data.get("action"))
                        && "Missing permit".equals(data.get("reason")) && "admin-99".equals(data.get("actedBy"))));
    }

    @Test
    void invalidTransitionReturnsConflictWithoutWrites() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference history = mock(CollectionReference.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        DocumentReference historyRef = mock(DocumentReference.class);
        Transaction tx = mock(Transaction.class);
        DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("shops")).thenReturn(shops);
        when(db.collection("shopReviewHistory")).thenReturn(history);
        when(shops.document("shop-2")).thenReturn(shopRef);
        when(history.document()).thenReturn(historyRef);
        when(snapshot.exists()).thenReturn(true);
        when(snapshot.getString("status")).thenReturn("ACTIVE");
        when(tx.get(shopRef)).thenReturn(ApiFutures.immediateFuture(snapshot));
        runTransaction(db, tx);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new AdminShopService(firebase, mock(ShopDocumentRepository.class))
                        .transition("shop-2", "APPROVED", null, "admin-99"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("Cannot approved a shop with status ACTIVE.", error.getReason());
    }

    @Test
    void ownerCannotReadAnUnlinkedShop() {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        OwnerShopService service = new OwnerShopService(firebase);
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.getMine(null));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @SuppressWarnings("unchecked")
    private static void runTransaction(Firestore db, Transaction tx) throws Exception {
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try {
                return ApiFutures.immediateFuture(work.updateCallback(tx));
            } catch (Exception exception) {
                return ApiFutures.immediateFailedFuture(exception);
            }
        });
    }
}
