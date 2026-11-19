package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopDocumentRepositoryTest {
    private static final String LAST_DOCUMENT_MESSAGE =
            "A registration needs at least one document. Upload a new file first, then remove this one.";

    @Test
    void deletingLastDocumentReturnsConflictWithoutDeletingIt() throws Exception {
        Fixture fixture = fixture(1L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> fixture.repository.delete("shop-7", "doc-a", "user-41"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(LAST_DOCUMENT_MESSAGE, error.getReason());
        verify(fixture.transaction, never()).delete(fixture.documentReference);
    }

    @Test
    void deletingDocumentIsAllowedWhenAtLeastOneWillRemain() throws Exception {
        Fixture fixture = fixture(2L);

        assertTrue(fixture.repository.delete("shop-7", "doc-a", "user-41"));

        verify(fixture.transaction).delete(fixture.documentReference);
    }

    private static Fixture fixture(long documentCount) throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference documents = mock(CollectionReference.class);
        DocumentReference shopReference = mock(DocumentReference.class);
        DocumentReference documentReference = mock(DocumentReference.class);
        Transaction transaction = mock(Transaction.class);
        DocumentSnapshot shopSnapshot = mock(DocumentSnapshot.class);
        DocumentSnapshot documentSnapshot = mock(DocumentSnapshot.class);

        when(firebase.firestore()).thenReturn(db);
        when(db.collection("shops")).thenReturn(shops);
        when(db.collection("shopDocuments")).thenReturn(documents);
        when(shops.document("shop-7")).thenReturn(shopReference);
        when(documents.document("doc-a")).thenReturn(documentReference);
        when(shopSnapshot.exists()).thenReturn(true);
        when(shopSnapshot.getString("status")).thenReturn("REJECTED");
        when(shopSnapshot.getLong("documentCount")).thenReturn(documentCount);
        when(documentSnapshot.exists()).thenReturn(true);
        when(documentSnapshot.getString("shopId")).thenReturn("shop-7");
        when(documentSnapshot.getString("uploadedBy")).thenReturn("user-41");
        when(transaction.get(shopReference)).thenReturn(ApiFutures.immediateFuture(shopSnapshot));
        when(transaction.get(documentReference)).thenReturn(ApiFutures.immediateFuture(documentSnapshot));
        runTransaction(db, transaction);

        return new Fixture(new ShopDocumentRepository(firebase), transaction, documentReference);
    }

    @SuppressWarnings("unchecked")
    private static void runTransaction(Firestore db, Transaction transaction) throws Exception {
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try {
                return ApiFutures.immediateFuture(work.updateCallback(transaction));
            } catch (Exception exception) {
                return ApiFutures.immediateFailedFuture(exception);
            }
        });
    }

    private record Fixture(ShopDocumentRepository repository, Transaction transaction,
            DocumentReference documentReference) {}
}
