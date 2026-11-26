package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;

class ReadyNotificationOutboxTest {
    @Test
    void readyTransitionCreatesStableOutboxEventInSameTransaction() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class), orders = mock(CollectionReference.class);
        CollectionReference history = mock(CollectionReference.class), outbox = mock(CollectionReference.class), stageRequests = mock(CollectionReference.class);
        DocumentReference shopRef = mock(DocumentReference.class), orderRef = mock(DocumentReference.class);
        DocumentReference historyRef = mock(DocumentReference.class), eventRef = mock(DocumentReference.class), stageRequestRef = mock(DocumentReference.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class), order = mock(DocumentSnapshot.class);
        Transaction tx = mock(Transaction.class);
        when(firebase.firestore()).thenReturn(db); when(db.collection("shops")).thenReturn(shops);
        when(db.collection("orders")).thenReturn(orders); when(db.collection("orderStageHistory")).thenReturn(history);
        when(db.collection("notificationOutbox")).thenReturn(outbox); when(shops.document("shop-A")).thenReturn(shopRef);
        when(orders.document("order-A")).thenReturn(orderRef); when(history.document()).thenReturn(historyRef);
        when(orderRef.collection("stageAdvanceRequests")).thenReturn(stageRequests); when(stageRequests.document(any(String.class))).thenReturn(stageRequestRef);
        when(outbox.document("order-A_READY")).thenReturn(eventRef); when(shop.exists()).thenReturn(true);
        when(shop.getString("status")).thenReturn("ACTIVE"); when(order.exists()).thenReturn(true);
        when(order.getId()).thenReturn("order-A"); when(order.getString("shopId")).thenReturn("shop-A");
        when(order.getString("stage")).thenReturn("FOLDING"); when(order.getString("customerUserId")).thenReturn("customer-user");
        when(order.getString("customerName")).thenReturn("Customer"); when(order.getString("orderCode")).thenReturn("O-001");
        when(order.get("kilos")).thenReturn(new BigDecimal("2.00")); when(order.get("totalPrice")).thenReturn(new BigDecimal("120.00"));
        when(tx.get(shopRef)).thenReturn(ApiFutures.immediateFuture(shop)); when(tx.get(orderRef)).thenReturn(ApiFutures.immediateFuture(order));
        when(tx.get(stageRequestRef)).thenReturn(ApiFutures.immediateFuture(mock(DocumentSnapshot.class)));
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(work.updateCallback(tx)); }
            catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
        });

        new ShopOrderService(firebase).advance(new FirebasePrincipal("staff-uid", "s@example.com", "Staff", "staff-user", "STAFF", "shop-A", "ACTIVE"), "order-A",
                new ShopOrderService.ExpectedStageRequest("FOLDING", "00000000-0000-4000-8000-000000000004"));

        verify(tx).create(eq(eventRef), argThat((Map<String, Object> event) ->
                "READY".equals(event.get("eventType")) && "PENDING".equals(event.get("status"))
                        && "order-A".equals(event.get("orderId")) && "customer-user".equals(event.get("customerUserId"))));
        verify(tx).create(eq(historyRef), any());
    }
}
