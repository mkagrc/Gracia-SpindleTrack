package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    void createsOrderWithServerSelectedActiveServiceAndStaffEnteredPrice() throws Exception {
        Fixture f = new Fixture();
        when(f.counterSnapshot.exists()).thenReturn(true);
        when(f.counterSnapshot.getLong("lastNumber")).thenReturn(7L);
        f.runTransaction();
        ShopOrderService.OrderSummary first = new ShopOrderService(f.firebase).create(f.staff(), f.request());
        assertEquals("O-000008", first.orderCode());
        assertEquals("A Person", first.customerName());
        assertEquals("639171234567", first.mobile());
        assertEquals("svc-string-A", first.serviceId());
        assertEquals("Wash and fold", first.serviceName());
        assertEquals(new BigDecimal("2.35"), first.kilos());
        assertEquals(new BigDecimal("30.75"), first.totalPrice());
        assertEquals(LaundryStage.RECEIVED, first.stage());
        verify(f.transaction).create(eq(f.orderRef), argThat(values -> !values.containsKey("items")
                && !values.containsKey("total") && new BigDecimal("30.75").equals(values.get("totalPrice"))));
        verify(f.transaction).create(eq(f.priceLogRef), any());
    }

    @Test
    void inactiveOrOtherShopServiceCannotBeUsedAndNoOrderIsWritten() throws Exception {
        Fixture f = new Fixture();
        when(f.serviceSnapshot.getString("shopId")).thenReturn("shop-string-B");
        f.runTransaction();
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.staff(), f.request()));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(f.transaction, never()).create(any(DocumentReference.class), any());

        when(f.serviceSnapshot.getString("shopId")).thenReturn("shop-string-A");
        when(f.serviceSnapshot.getBoolean("active")).thenReturn(false);
        ResponseStatusException inactive = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.staff(), f.request()));
        assertEquals(HttpStatus.CONFLICT, inactive.getStatusCode());
        verify(f.transaction, never()).create(any(DocumentReference.class), any());
    }

    @Test
    void foreignOrderIsNotFoundAndListQueryIsScopedToCallerShop() throws Exception {
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
    void staffAdvancesExactlyOneStageAndWritesDisplayName() throws Exception {
        Fixture f = new Fixture();
        f.stubOwnedOrder("order-id-X", "shop-string-A", "RECEIVED");
        f.runTransaction();
        ShopOrderService.OrderSummary moved = new ShopOrderService(f.firebase).advance(f.staff(), "order-id-X");
        assertEquals(LaundryStage.WASHING, moved.stage());
        verify(f.transaction).update(eq(f.orderRef), argThat(values -> "WASHING".equals(values.get("stage"))));
        verify(f.transaction).create(eq(f.historyRef), argThat(values -> "Staff".equals(values.get("changedByName"))));
        when(f.orderSnapshot.getString("stage")).thenReturn("CLAIMED");
        ResponseStatusException finalStage = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).advance(f.staff(), "order-id-X"));
        assertEquals(HttpStatus.CONFLICT, finalStage.getStatusCode());
    }

    @Test
    void inactiveShopBlocksCreateAndOwnerCannotUseWriteMethods() throws Exception {
        Fixture f = new Fixture();
        when(f.shopSnapshot.getString("status")).thenReturn("DEACTIVATED");
        f.runTransaction();
        ResponseStatusException inactive = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.staff(), f.request()));
        assertEquals(HttpStatus.FORBIDDEN, inactive.getStatusCode());
        ShopOrderService service = new ShopOrderService(f.firebase);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.create(f.owner(), f.request())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.changePrice(f.owner(), "order-id-X", new ShopOrderService.PriceChangeRequest(new BigDecimal("10.00")))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.advance(f.owner(), "order-id-X")).getStatusCode());
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
        final DocumentReference foreignOrder = mock(DocumentReference.class);
        final DocumentReference historyRef = mock(DocumentReference.class);
        final DocumentReference priceLogRef = mock(DocumentReference.class);
        final CollectionReference priceLogCollection = mock(CollectionReference.class);
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
            when(orders.document()).thenReturn(orderRef);
            when(orders.document("order-id-X")).thenReturn(orderRef);
            when(orders.document("foreign-order-id")).thenReturn(foreignOrder);
            when(orderRef.getId()).thenReturn("order-id-A");
            when(history.document()).thenReturn(historyRef);
            when(orderRef.collection("priceLog")).thenReturn(priceLogCollection);
            when(priceLogCollection.document()).thenReturn(priceLogRef);
            when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shopSnapshot));
            when(shopSnapshot.exists()).thenReturn(true);
            when(shopSnapshot.getString("status")).thenReturn("ACTIVE");
            when(serviceSnapshot.exists()).thenReturn(true);
            when(serviceSnapshot.getId()).thenReturn("svc-string-A");
            when(serviceSnapshot.getString("shopId")).thenReturn("shop-string-A");
            when(serviceSnapshot.getBoolean("active")).thenReturn(true);
            when(serviceSnapshot.getString("name")).thenReturn("Wash and fold");
            when(serviceSnapshot.getString("unit")).thenReturn("kg");
        }

        ShopOrderService.CreateOrderRequest request() {
            return new ShopOrderService.CreateOrderRequest("  A Person  ", "+63 (917) 123-4567", "svc-string-A",
                    new BigDecimal("2.35"), new BigDecimal("30.75"), "");
        }
        void stubOwnedOrder(String id, String shopId, String stage) throws Exception {
            when(orderSnapshot.exists()).thenReturn(true);
            when(orderSnapshot.getId()).thenReturn(id);
            when(orderSnapshot.getString("shopId")).thenReturn(shopId);
            when(orderSnapshot.getString("stage")).thenReturn(stage);
            when(orderSnapshot.getString("serviceId")).thenReturn("svc-string-A");
            when(orderSnapshot.getString("serviceName")).thenReturn("Wash and fold");
            when(orderSnapshot.getString("customerName")).thenReturn("A Person");
            when(orderSnapshot.getString("mobile")).thenReturn("639171234567");
            when(orderSnapshot.getString("orderCode")).thenReturn("O-000001");
            when(orderSnapshot.getString("notes")).thenReturn("");
            when(orderSnapshot.getString("createdBy")).thenReturn("staff-user-id");
            when(orderSnapshot.get("kilos")).thenReturn(new BigDecimal("2.35"));
            when(orderSnapshot.get("totalPrice")).thenReturn(new BigDecimal("30.75"));
        }
        @SuppressWarnings("unchecked")
        void runTransaction() throws Exception {
            when(transaction.get(any(DocumentReference.class))).thenAnswer(invocation -> {
                DocumentReference ref = invocation.getArgument(0);
                if (ref == shopRef) return ApiFutures.immediateFuture(shopSnapshot);
                if (ref == counterRef) return ApiFutures.immediateFuture(counterSnapshot);
                if (ref == serviceRef) return ApiFutures.immediateFuture(serviceSnapshot);
                if (ref == orderRef) return ApiFutures.immediateFuture(orderSnapshot);
                return ApiFutures.immediateFuture(mock(DocumentSnapshot.class));
            });
            when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
                Transaction.Function<Object> work = invocation.getArgument(0);
                try { return ApiFutures.immediateFuture(work.updateCallback(transaction)); }
                catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
            });
        }
        FirebasePrincipal staff() { return new FirebasePrincipal("staff-uid", "staff@example.com", "Staff", "staff-user-id", "STAFF", "shop-string-A", "ACTIVE"); }
        FirebasePrincipal owner() { return new FirebasePrincipal("owner-uid", "owner@example.com", "Owner", "owner-user-id", "OWNER", "shop-string-A", "ACTIVE"); }
    }
}
