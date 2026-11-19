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
import java.util.Map;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopOrderPricingServiceTest {
    @Test
    void createUsesManuallyEnteredKilosAndPriceAndAppendsInitialPriceAndNamedStageEntries() throws Exception {
        Fixture f = new Fixture();
        f.transactionHarness();
        ShopOrderService service = new ShopOrderService(f.firebase);
        ShopOrderService.CreateOrderRequest request = new ShopOrderService.CreateOrderRequest("Customer", "639171234567",
                "service-A", new BigDecimal("2.35"), new BigDecimal("123.45"), "");

        ShopOrderService.OrderSummary created = service.create(f.staff(), request);

        assertEquals(new BigDecimal("2.35"), created.kilos());
        assertEquals(new BigDecimal("123.45"), created.totalPrice());
        assertEquals("Wash", created.serviceName());
        verify(f.tx).create(eq(f.order), argThat(values -> values.get("totalPrice").equals(new BigDecimal("123.45"))
                && !values.containsKey("items") && !values.containsKey("total")));
        verify(f.tx).create(eq(f.priceLog), argThat(values -> values.get("previousPrice") == null
                && values.get("newPrice").equals(new BigDecimal("123.45"))
                && "staff-uid".equals(values.get("changedByUid")) && "Staff Display".equals(values.get("changedByName"))));
        verify(f.tx).create(eq(f.stageHistory), argThat(values -> "staff-user".equals(values.get("changedBy"))
                && "Staff Display".equals(values.get("changedByName"))));
    }

    @Test
    void priceChangeUpdatesOrderAndCreatesAnAppendOnlyAuditEntryInTheSameTransaction() throws Exception {
        Fixture f = new Fixture();
        f.prepareExistingOrder("shop-A");
        f.transactionHarness();

        ShopOrderService.OrderSummary result = new ShopOrderService(f.firebase).changePrice(f.staff(), "order-A",
                new ShopOrderService.PriceChangeRequest(new BigDecimal("88.20")));

        assertEquals(new BigDecimal("88.20"), result.totalPrice());
        verify(f.tx).update(eq(f.order), argThat(values -> new BigDecimal("88.20").equals(values.get("totalPrice"))));
        verify(f.tx).create(eq(f.priceLog), argThat(values -> new BigDecimal("123.45").equals(values.get("previousPrice"))
                && new BigDecimal("88.20").equals(values.get("newPrice"))
                && "staff-uid".equals(values.get("changedByUid")) && "Staff Display".equals(values.get("changedByName"))));
    }

    @Test
    void priceChangeReturnsNotFoundForAnotherShopAndDoesNotWrite() throws Exception {
        Fixture f = new Fixture();
        f.prepareExistingOrder("shop-B");
        f.transactionHarness();
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).changePrice(f.staff(), "order-A",
                        new ShopOrderService.PriceChangeRequest(new BigDecimal("88.20"))));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(f.tx, never()).update(any(DocumentReference.class), any(Map.class));
        verify(f.tx, never()).create(any(DocumentReference.class), any());
    }

    @Test
    void invalidKilosAndPriceAndOwnerPriceChangeAreRejected() throws Exception {
        Fixture f = new Fixture();
        ShopOrderService service = new ShopOrderService(f.firebase);
        for (String kilos : new String[] { "0", "1.001", "1000.01" }) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.create(f.staff(), new ShopOrderService.CreateOrderRequest("Customer", "639171234567", "service-A",
                            new BigDecimal(kilos), new BigDecimal("12.00"), ""))).getStatusCode());
        }
        for (String price : new String[] { "-0.01", "12.001", "1000000.01" }) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.changePrice(f.staff(), "order-A", new ShopOrderService.PriceChangeRequest(new BigDecimal(price)))).getStatusCode());
        }
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.changePrice(f.owner(), "order-A", new ShopOrderService.PriceChangeRequest(new BigDecimal("12.00")))).getStatusCode());
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference shops = mock(CollectionReference.class);
        final CollectionReference services = mock(CollectionReference.class);
        final CollectionReference counters = mock(CollectionReference.class);
        final CollectionReference orders = mock(CollectionReference.class);
        final CollectionReference stageHistoryCollection = mock(CollectionReference.class);
        final CollectionReference priceLogCollection = mock(CollectionReference.class);
        final DocumentReference shop = mock(DocumentReference.class);
        final DocumentReference service = mock(DocumentReference.class);
        final DocumentReference counter = mock(DocumentReference.class);
        final DocumentReference order = mock(DocumentReference.class);
        final DocumentReference stageHistory = mock(DocumentReference.class);
        final DocumentReference priceLog = mock(DocumentReference.class);
        final DocumentSnapshot shopSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot serviceSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot counterSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot orderSnapshot = mock(DocumentSnapshot.class);
        final Transaction tx = mock(Transaction.class);

        Fixture() throws Exception {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shops")).thenReturn(shops);
            when(db.collection("services")).thenReturn(services);
            when(db.collection("orderCounters")).thenReturn(counters);
            when(db.collection("orders")).thenReturn(orders);
            when(db.collection("orderStageHistory")).thenReturn(stageHistoryCollection);
            when(shops.document("shop-A")).thenReturn(shop);
            when(services.document("service-A")).thenReturn(service);
            when(counters.document("shop-A")).thenReturn(counter);
            when(orders.document()).thenReturn(order);
            when(orders.document("order-A")).thenReturn(order);
            when(order.getId()).thenReturn("order-A");
            when(stageHistoryCollection.document()).thenReturn(stageHistory);
            when(order.collection("priceLog")).thenReturn(priceLogCollection);
            when(priceLogCollection.document()).thenReturn(priceLog);
            when(shop.get()).thenReturn(ApiFutures.immediateFuture(shopSnapshot));
            when(shopSnapshot.exists()).thenReturn(true);
            when(shopSnapshot.getString("status")).thenReturn("ACTIVE");
            when(serviceSnapshot.exists()).thenReturn(true);
            when(serviceSnapshot.getId()).thenReturn("service-A");
            when(serviceSnapshot.getString("shopId")).thenReturn("shop-A");
            when(serviceSnapshot.getString("name")).thenReturn("Wash");
            when(serviceSnapshot.getBoolean("active")).thenReturn(true);
        }

        void prepareExistingOrder(String ownerShop) throws Exception {
            when(orderSnapshot.exists()).thenReturn(true);
            when(orderSnapshot.getId()).thenReturn("order-A");
            when(orderSnapshot.getString("shopId")).thenReturn(ownerShop);
            when(orderSnapshot.getString("orderCode")).thenReturn("O-000001");
            when(orderSnapshot.getString("customerName")).thenReturn("Customer");
            when(orderSnapshot.getString("mobile")).thenReturn("639171234567");
            when(orderSnapshot.getString("serviceId")).thenReturn("service-A");
            when(orderSnapshot.getString("serviceName")).thenReturn("Wash");
            when(orderSnapshot.getString("stage")).thenReturn("RECEIVED");
            when(orderSnapshot.getString("createdBy")).thenReturn("staff-user");
            when(orderSnapshot.get("kilos")).thenReturn(new BigDecimal("2.35"));
            when(orderSnapshot.get("totalPrice")).thenReturn(new BigDecimal("123.45"));
        }

        @SuppressWarnings("unchecked")
        void transactionHarness() throws Exception {
            when(tx.get(any(DocumentReference.class))).thenAnswer(invocation -> {
                DocumentReference ref = invocation.getArgument(0);
                if (ref == shop) return ApiFutures.immediateFuture(shopSnapshot);
                if (ref == counter) return ApiFutures.immediateFuture(counterSnapshot);
                if (ref == service) return ApiFutures.immediateFuture(serviceSnapshot);
                if (ref == order) return ApiFutures.immediateFuture(orderSnapshot);
                return ApiFutures.immediateFuture(mock(DocumentSnapshot.class));
            });
            when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
                Transaction.Function<Object> work = invocation.getArgument(0);
                try { return ApiFutures.immediateFuture(work.updateCallback(tx)); }
                catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
            });
        }

        FirebasePrincipal staff() { return new FirebasePrincipal("staff-uid", "staff@example.com", "Staff Display", "staff-user", "STAFF", "shop-A", "ACTIVE"); }
        FirebasePrincipal owner() { return new FirebasePrincipal("owner-uid", "owner@example.com", "Owner", "owner-user", "OWNER", "shop-A", "ACTIVE"); }
    }
}
