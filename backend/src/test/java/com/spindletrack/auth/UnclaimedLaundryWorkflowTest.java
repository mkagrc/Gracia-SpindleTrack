package com.spindletrack.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

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
import org.junit.jupiter.api.Test;

class UnclaimedLaundryWorkflowTest {
    @Test
    void dailyScanMarksOnlyThreeDayReadyOrderAndEnqueuesStableEvent() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference orders = mock(CollectionReference.class), outbox = mock(CollectionReference.class);
        Query readyQuery = mock(Query.class); QuerySnapshot readyRows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot candidate = mock(QueryDocumentSnapshot.class); DocumentSnapshot current = mock(DocumentSnapshot.class);
        DocumentReference orderRef = mock(DocumentReference.class), eventRef = mock(DocumentReference.class);
        Transaction tx = mock(Transaction.class);
        when(firebase.firestore()).thenReturn(db); when(db.collection("orders")).thenReturn(orders);
        when(db.collection("notificationOutbox")).thenReturn(outbox); when(orders.whereEqualTo("stage", "READY")).thenReturn(readyQuery);
        when(readyQuery.get()).thenReturn(ApiFutures.immediateFuture(readyRows)); when(readyRows.getDocuments()).thenReturn(java.util.List.of(candidate));
        when(candidate.getId()).thenReturn("order-ready"); when(candidate.getString("shopId")).thenReturn("shop-A");
        when(candidate.get("readyAt")).thenReturn(Timestamp.of(Date.from(Instant.now().minusSeconds(4L * 24 * 60 * 60))));
        when(candidate.getString("customerUserId")).thenReturn("customer-A");
        when(orders.document("order-ready")).thenReturn(orderRef); when(outbox.document("order-ready_UNCLAIMED")).thenReturn(eventRef);
        when(tx.get(orderRef)).thenReturn(ApiFutures.immediateFuture(current)); when(current.exists()).thenReturn(true);
        when(current.getString("shopId")).thenReturn("shop-A"); when(current.getString("stage")).thenReturn("READY");
        when(current.getBoolean("unclaimed")).thenReturn(false); when(current.getString("customerUserId")).thenReturn("customer-A");
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(work.updateCallback(tx)); }
            catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
        });

        new OwnerOperationsService(firebase).scanUnclaimedDaily();

        verify(tx).update(eq(orderRef), argThat((Map<String, Object> values) -> Boolean.TRUE.equals(values.get("unclaimed"))));
        verify(tx).create(eq(eventRef), argThat((Map<String, Object> values) -> "UNCLAIMED".equals(values.get("eventType"))
                && "PENDING".equals(values.get("status")) && "customer-A".equals(values.get("customerUserId"))));
    }

    @Test
    void dailyScanDoesNotMarkRecentReadyOrder() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class); Firestore db = mock(Firestore.class);
        CollectionReference orders = mock(CollectionReference.class); Query query = mock(Query.class); QuerySnapshot rows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot recent = mock(QueryDocumentSnapshot.class); when(firebase.firestore()).thenReturn(db);
        when(db.collection("orders")).thenReturn(orders); when(orders.whereEqualTo("stage", "READY")).thenReturn(query);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(rows)); when(rows.getDocuments()).thenReturn(java.util.List.of(recent));
        when(recent.get("readyAt")).thenReturn(Timestamp.of(Date.from(Instant.now())));
        new OwnerOperationsService(firebase).scanUnclaimedDaily();
        verify(db, never()).runTransaction(any(Transaction.Function.class));
    }
}
