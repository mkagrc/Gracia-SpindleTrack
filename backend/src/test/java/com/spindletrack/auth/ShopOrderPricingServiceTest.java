package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        assertEquals("UNPAID", created.paymentStatus());
        assertEquals(null, created.paymentMethod());
        assertEquals(null, created.paidAt());
        assertEquals(null, created.paidByUid());
        assertEquals(null, created.paidByName());
        verify(f.tx).create(eq(f.order), argThat(values -> values.get("totalPrice").equals(new BigDecimal("123.45"))
                && "UNPAID".equals(values.get("paymentStatus")) && values.get("paymentMethod") == null
                && values.get("paidAt") == null && values.get("paidByUid") == null && values.get("paidByName") == null
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
        assertEquals("UNPAID", result.paymentStatus());
        verify(f.tx).update(eq(f.order), argThat(values -> new BigDecimal("88.20").equals(values.get("totalPrice"))));
        verify(f.tx).create(eq(f.priceLog), argThat(values -> new BigDecimal("123.45").equals(values.get("previousPrice"))
                && new BigDecimal("88.20").equals(values.get("newPrice"))
                && "staff-uid".equals(values.get("changedByUid")) && "Staff Display".equals(values.get("changedByName"))));
    }

    @Test
    void legacyOrderWithoutPaymentFieldsIsTreatedAsUnpaid() throws Exception {
        Fixture f = new Fixture();
        f.prepareExistingOrder("shop-A");
        f.transactionHarness();

        ShopOrderService.OrderSummary legacy = new ShopOrderService(f.firebase).changePrice(f.staff(), "order-A",
                new ShopOrderService.PriceChangeRequest(new BigDecimal("88.20")));

        assertEquals("UNPAID", legacy.paymentStatus());
        assertEquals(null, legacy.paymentMethod());
        assertEquals(null, legacy.paidAt());
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
    void paidOrderRejectsPriceChangeInsideTheTransaction() throws Exception {
        Fixture f = new Fixture();
        f.prepareExistingOrder("shop-A");
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PAID");
        f.transactionHarness();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).changePrice(f.staff(), "order-A",
                        new ShopOrderService.PriceChangeRequest(new BigDecimal("88.20"))));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(f.tx, never()).update(any(DocumentReference.class), any(Map.class));
        verify(f.tx, never()).create(any(DocumentReference.class), any());
    }

    @Test
    void claimRequiresPaidButAnEarlierStageCanAdvanceWhileUnpaid() throws Exception {
        Fixture f = new Fixture();
        f.prepareExistingOrder("shop-A");
        when(f.orderSnapshot.getString("stage")).thenReturn("READY");
        f.transactionHarness();
        ShopOrderService service = new ShopOrderService(f.firebase);

        ResponseStatusException unpaid = assertThrows(ResponseStatusException.class,
                () -> service.advance(f.staff(), "order-A"));
        assertEquals(HttpStatus.CONFLICT, unpaid.getStatusCode());
        verify(f.tx, never()).create(eq(f.stageHistory), any());

        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PAID");
        assertEquals(com.spindletrack.LaundryStage.CLAIMED, service.advance(f.staff(), "order-A").stage());
        verify(f.tx).update(eq(f.order), argThat(values -> "CLAIMED".equals(values.get("stage"))));
    }

    @Test
    void cashPaymentMarksPaidOnceAndRejectsRepeat() throws Exception {
        Fixture f = new Fixture();
        f.prepareExistingOrder("shop-A");
        f.transactionHarness();
        ShopOrderService service = new ShopOrderService(f.firebase);

        ShopOrderService.OrderSummary paid = service.recordCashPayment(f.staff(), "order-A");
        assertEquals("PAID", paid.paymentStatus());
        assertEquals("CASH", paid.paymentMethod());
        assertEquals("staff-uid", paid.paidByUid());
        assertEquals("Staff Display", paid.paidByName());
        verify(f.tx).update(eq(f.order), argThat(values -> "PAID".equals(values.get("paymentStatus"))
                && "CASH".equals(values.get("paymentMethod"))
                && values.containsKey("paidAt")
                && "staff-uid".equals(values.get("paidByUid"))
                && "Staff Display".equals(values.get("paidByName"))));

        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PAID");
        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
                () -> service.recordCashPayment(f.staff(), "order-A"));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        verify(f.tx, times(1)).update(eq(f.order), any(Map.class));
    }

    @Test
    void cashPaymentRejectsForeignShopAndOwner() throws Exception {
        Fixture f = new Fixture();
        f.prepareExistingOrder("shop-B");
        f.transactionHarness();
        ShopOrderService service = new ShopOrderService(f.firebase);
        ResponseStatusException notFound = assertThrows(ResponseStatusException.class,
                () -> service.recordCashPayment(f.staff(), "order-A"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        verify(f.tx, never()).update(any(DocumentReference.class), any(Map.class));

        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> service.recordCashPayment(f.owner(), "order-A"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
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

    @Test
    void pendingPaymentLinkAlsoLocksPriceUntilCancelled() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A");
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING"); f.transactionHarness();
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).changePrice(f.staff(), "order-A", new ShopOrderService.PriceChangeRequest(new BigDecimal("90.00"))));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(f.tx, never()).update(any(DocumentReference.class), any(Map.class));
    }

    @Test
    void onlineLinkStoresPendingAmountAndOrderReferenceAndConfirmsOnlyMatchingPayment() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        PayMongoGateway gateway = mock(PayMongoGateway.class);
        when(gateway.createPaymentLink(12345L, "order-A", "O-000001")).thenReturn(new PayMongoGateway.CreatedLink("link-abc", "https://pm.link/test", 12345L, "PHP", "ref-1"));
        ShopOrderService service = new ShopOrderService(f.firebase, gateway);
        ShopOrderService.PaymentLinkResult created = service.createPaymentLink(f.staff(), "order-A");
        assertEquals("PENDING", created.order().paymentStatus());
        assertEquals("https://pm.link/test", created.paymentLinkUrl());
        verify(f.tx).update(eq(f.order), argThat(values -> "PENDING".equals(values.get("paymentStatus")) && "ONLINE".equals(values.get("paymentMethod")) && Long.valueOf(12345L).equals(values.get("paymentAmountCentavos")) && "order-A".equals(values.get("paymentOrderIdReference"))));
        verify(f.tx).create(eq(f.paymentRecord), argThat(values -> "shop-A".equals(values.get("shopId")) && "order-A".equals(values.get("orderId")) && "PENDING".equals(values.get("status"))));

        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paymentLinkId")).thenReturn("link-abc");
        when(f.orderSnapshot.getString("paymentOrderIdReference")).thenReturn("order-A");
        when(f.orderSnapshot.getLong("paymentAmountCentavos")).thenReturn(12345L);
        when(f.orderSnapshot.getString("paymentRecordId")).thenReturn("payment-doc-A");
        when(gateway.findPaidPayment("link-abc")).thenReturn(new PayMongoGateway.VerifiedPayment("pay-1", "paid", 12345L, "order-A"));
        ShopOrderService.OrderSummary paid = service.confirmOnlinePayment(f.staff(), "order-A");
        assertEquals("PAID", paid.paymentStatus()); assertEquals("ONLINE", paid.paymentMethod());
        verify(f.tx).update(eq(f.order), argThat(values -> "PAID".equals(values.get("paymentStatus")) && "ONLINE".equals(values.get("paymentMethod"))));
    }

    @Test
    void onlineConfirmationRejectsWrongAmountOrOrderReference() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A");
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paymentLinkId")).thenReturn("link-abc");
        when(f.orderSnapshot.getString("paymentOrderIdReference")).thenReturn("order-A");
        when(f.orderSnapshot.getLong("paymentAmountCentavos")).thenReturn(12345L); f.transactionHarness();
        PayMongoGateway gateway = mock(PayMongoGateway.class);
        when(gateway.findPaidPayment("link-abc")).thenReturn(new PayMongoGateway.VerifiedPayment("pay-1", "paid", 12344L, "other-order"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> new ShopOrderService(f.firebase, gateway).confirmOnlinePayment(f.staff(), "order-A"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(f.tx, never()).update(any(DocumentReference.class), any(Map.class));
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference shops = mock(CollectionReference.class);
        final CollectionReference services = mock(CollectionReference.class);
        final CollectionReference counters = mock(CollectionReference.class);
        final CollectionReference orders = mock(CollectionReference.class);
        final CollectionReference payments = mock(CollectionReference.class);
        final CollectionReference stageHistoryCollection = mock(CollectionReference.class);
        final CollectionReference priceLogCollection = mock(CollectionReference.class);
        final DocumentReference shop = mock(DocumentReference.class);
        final DocumentReference service = mock(DocumentReference.class);
        final DocumentReference counter = mock(DocumentReference.class);
        final DocumentReference order = mock(DocumentReference.class);
        final DocumentReference stageHistory = mock(DocumentReference.class);
        final DocumentReference priceLog = mock(DocumentReference.class);
        final DocumentReference paymentRecord = mock(DocumentReference.class);
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
            when(db.collection("payments")).thenReturn(payments);
            when(db.collection("orderStageHistory")).thenReturn(stageHistoryCollection);
            when(shops.document("shop-A")).thenReturn(shop);
            when(services.document("service-A")).thenReturn(service);
            when(counters.document("shop-A")).thenReturn(counter);
            when(orders.document()).thenReturn(order);
            when(orders.document("order-A")).thenReturn(order);
            when(payments.document()).thenReturn(paymentRecord);
            when(paymentRecord.getId()).thenReturn("payment-doc-A");
            when(payments.document("payment-doc-A")).thenReturn(paymentRecord);
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
            when(order.get()).thenReturn(ApiFutures.immediateFuture(orderSnapshot));
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
