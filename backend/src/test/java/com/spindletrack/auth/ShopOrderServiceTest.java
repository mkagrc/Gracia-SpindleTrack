package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;
import com.spindletrack.LaundryStage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopOrderServiceTest {
    @Test
    void orderCodesIncrementInTransactionAndTotalUsesActiveServiceSnapshotPrices() throws Exception {
        Fixture f = new Fixture();
        when(f.counterSnapshot.exists()).thenReturn(true);
        when(f.counterSnapshot.getLong("lastNumber")).thenReturn(7L, 7L, 8L, 8L);
        runTransaction(f.db, f.transaction);
        ShopOrderService service = new ShopOrderService(f.firebase);
        ShopOrderService.CreateOrderRequest request = request("  A Person  ", "+63 (917) 123-4567", 3);

        ShopOrderService.OrderSummary first = service.create(f.staff(), request);
        ShopOrderService.OrderSummary second = service.create(f.staff(), request);

        assertEquals("O-000008", first.orderCode());
        assertEquals("O-000009", second.orderCode());
        assertNotEquals(first.orderCode(), second.orderCode());
        assertEquals("A Person", first.customerName());
        assertEquals("639171234567", first.mobile());
        assertEquals(new BigDecimal("30.75"), first.total());
        assertEquals(new BigDecimal("30.75"), first.items().get(0).lineTotal());
        assertEquals(LaundryStage.RECEIVED, first.stage());
        verify(f.services, atLeastOnce()).document("svc-string-A");
    }

    @Test
    void inactiveOrOtherShopServiceCannotBeUsedAndNoOrderIsWritten() throws Exception {
        Fixture f = new Fixture();
        when(f.serviceSnapshot.getString("shopId")).thenReturn("shop-string-B");
        runTransaction(f.db, f.transaction);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.staff(), request("A Person", "639171234567", 1)));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(f.transaction, never()).create(any(DocumentReference.class), any());

        when(f.serviceSnapshot.getString("shopId")).thenReturn("shop-string-A");
        when(f.serviceSnapshot.getBoolean("active")).thenReturn(false);
        ResponseStatusException inactive = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.staff(), request("A Person", "639171234567", 1)));
        assertEquals(HttpStatus.CONFLICT, inactive.getStatusCode());
        verify(f.transaction, never()).create(any(DocumentReference.class), any());
    }

    @Test
    void orderReadIsScopedToCurrentShopAndListQueriesOnlyCurrentShop() throws Exception {
        Fixture f = new Fixture();
        when(f.foreignOrderSnapshot.exists()).thenReturn(true);
        when(f.foreignOrderSnapshot.getString("shopId")).thenReturn("shop-string-B");
        when(f.orders.document("foreign-order-id")).thenReturn(f.foreignOrder);
        when(f.foreignOrder.get()).thenReturn(ApiFutures.immediateFuture(f.foreignOrderSnapshot));
        ResponseStatusException notFound = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).get(f.owner(), "foreign-order-id"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        Query query = mock(Query.class);
        QuerySnapshot rows = mock(QuerySnapshot.class);
        when(f.orders.whereEqualTo("shopId", "shop-string-A")).thenReturn(query);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(rows));
        when(rows.getDocuments()).thenReturn(List.of());
        assertEquals(0L, new ShopOrderService(f.firebase).list(f.owner(), null, null, 0).totalElements());
        verify(f.orders).whereEqualTo("shopId", "shop-string-A");
    }

    @Test
    void staffAdvancesExactlyOneStageAndClaimedCannotAdvanceAgain() throws Exception {
        Fixture f = new Fixture();
        when(f.orderSnapshot.exists()).thenReturn(true);
        when(f.orderSnapshot.getId()).thenReturn("order-id-X");
        when(f.orderSnapshot.getString("shopId")).thenReturn("shop-string-A");
        when(f.orderSnapshot.getString("stage")).thenReturn("RECEIVED");
        when(f.orderSnapshot.get("items")).thenReturn(List.of());
        when(f.orderSnapshot.get("total")).thenReturn(0.0);
        when(f.orderSnapshot.getString("customerName")).thenReturn("A Person");
        when(f.orderSnapshot.getString("mobile")).thenReturn("639171234567");
        when(f.orderSnapshot.getString("orderCode")).thenReturn("O-000001");
        when(f.orderSnapshot.getString("notes")).thenReturn("");
        when(f.orders.document("order-id-X")).thenReturn(f.orderRef);
        runTransaction(f.db, f.transaction);

        ShopOrderService.OrderSummary moved = new ShopOrderService(f.firebase).advance(f.staff(), "order-id-X");
        assertEquals(LaundryStage.WASHING, moved.stage());
        verify(f.transaction).update(eq(f.orderRef), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(values -> "WASHING".equals(values.get("stage"))));

        when(f.orderSnapshot.getString("stage")).thenReturn("CLAIMED");
        ResponseStatusException finalStage = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).advance(f.staff(), "order-id-X"));
        assertEquals(HttpStatus.CONFLICT, finalStage.getStatusCode());
    }

    @Test
    void inactiveShopBlocksCreateAndOwnerCannotUseWriteServiceMethods() throws Exception {
        Fixture f = new Fixture();
        when(f.shopSnapshot.getString("status")).thenReturn("DEACTIVATED");
        runTransaction(f.db, f.transaction);
        ResponseStatusException inactive = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.staff(), request("A Person", "639171234567", 1)));
        assertEquals(HttpStatus.FORBIDDEN, inactive.getStatusCode());
        ResponseStatusException ownerWrite = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.owner(), request("A Person", "639171234567", 1)));
        assertEquals(HttpStatus.FORBIDDEN, ownerWrite.getStatusCode());
    }

    private static ShopOrderService.CreateOrderRequest request(String name, String mobile, int quantity) {
        return new ShopOrderService.CreateOrderRequest(name, mobile,
                List.of(new ShopOrderService.ItemRequest("svc-string-A", quantity)), "");
    }

    @SuppressWarnings("unchecked")
    private static void runTransaction(Firestore db, Transaction transaction) throws Exception {
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(work.updateCallback(transaction)); }
            catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
        });
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference shops = mock(CollectionReference.class);
        final CollectionReference services = mock(CollectionReference.class);
        final CollectionReference counters = mock(CollectionReference.class);
        final CollectionReference orders = mock(CollectionReference.class);
        final CollectionReference history = mock(CollectionReference.class);
        final DocumentReference shopRef = mock(DocumentReference.class);
        final DocumentReference serviceRef = mock(DocumentReference.class);
        final DocumentReference counterRef = mock(DocumentReference.class);
        final DocumentReference orderRef = mock(DocumentReference.class);
        final DocumentReference secondOrderRef = mock(DocumentReference.class);
        final DocumentReference foreignOrder = mock(DocumentReference.class);
        final DocumentReference historyRef = mock(DocumentReference.class);
        final DocumentReference secondHistoryRef = mock(DocumentReference.class);
        final DocumentSnapshot shopSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot serviceSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot counterSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot orderSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot foreignOrderSnapshot = mock(DocumentSnapshot.class);
        final Transaction transaction = mock(Transaction.class);

        Fixture() throws Exception {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shops")).thenReturn(shops);
            when(db.collection("services")).thenReturn(services);
            when(db.collection("orderCounters")).thenReturn(counters);
            when(db.collection("orders")).thenReturn(orders);
            when(db.collection("orderStageHistory")).thenReturn(history);
            when(shops.document("shop-string-A")).thenReturn(shopRef);
            when(services.document("svc-string-A")).thenReturn(serviceRef);
            when(counters.document("shop-string-A")).thenReturn(counterRef);
            when(orders.document()).thenReturn(orderRef, secondOrderRef);
            when(orders.document("order-id-X")).thenReturn(orderRef);
            when(orders.document("foreign-order-id")).thenReturn(foreignOrder);
            when(orderRef.getId()).thenReturn("order-id-A");
            when(secondOrderRef.getId()).thenReturn("order-id-B");
            when(history.document()).thenReturn(historyRef, secondHistoryRef);
            when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shopSnapshot));
            when(shopSnapshot.exists()).thenReturn(true);
            when(shopSnapshot.getString("status")).thenReturn("ACTIVE");
            when(serviceSnapshot.exists()).thenReturn(true);
            when(serviceSnapshot.getId()).thenReturn("svc-string-A");
            when(serviceSnapshot.getString("shopId")).thenReturn("shop-string-A");
            when(serviceSnapshot.getBoolean("active")).thenReturn(true);
            when(serviceSnapshot.getDouble("pricePerUnit")).thenReturn(10.25);
            when(serviceSnapshot.getString("name")).thenReturn("Wash and fold");
            when(serviceSnapshot.getString("unit")).thenReturn("kg");
            when(transaction.get(any(DocumentReference.class))).thenAnswer(invocation -> {
                DocumentReference ref = invocation.getArgument(0);
                if (ref == shopRef) return ApiFutures.immediateFuture(shopSnapshot);
                if (ref == counterRef) return ApiFutures.immediateFuture(counterSnapshot);
                if (ref == serviceRef) return ApiFutures.immediateFuture(serviceSnapshot);
                if (ref == orderRef) return ApiFutures.immediateFuture(orderSnapshot);
                return ApiFutures.immediateFuture(mock(DocumentSnapshot.class));
            });
        }

        FirebasePrincipal staff() { return new FirebasePrincipal("staff-uid", "staff@example.com", "Staff", "staff-user-id", "STAFF", "shop-string-A", "ACTIVE"); }
        FirebasePrincipal owner() { return new FirebasePrincipal("owner-uid", "owner@example.com", "Owner", "owner-user-id", "OWNER", "shop-string-A", "ACTIVE"); }
    }
}
