package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopOrderBillingTypeTest {

    @Test
    void savedOrderMapContainsOnlyFirestoreSupportedTypesAndAllResponsesReturnQuantityAndUnit() throws Exception {
        record Case(String billingType, String unit, BigDecimal qty, BigDecimal actualWeight, String expectedTotal) {}
        List<Case> cases = List.of(
                new Case("PER_KG", "kg", new BigDecimal("3.50"), null, "52.50"),
                new Case("PER_LOAD", "load", new BigDecimal("2"), new BigDecimal("6.25"), "30.00"),
                new Case("PER_PIECE", "piece", new BigDecimal("5"), null, "75.00"),
                new Case("FLAT", "flat", new BigDecimal("1"), null, "15.00")
        );

        for (Case c : cases) {
            Fixture f = new Fixture();
            when(f.serviceSnapshot.getString("billingType")).thenReturn(c.billingType());
            when(f.serviceSnapshot.getString("unit")).thenReturn(c.unit());
            if ("PER_LOAD".equals(c.billingType())) {
                when(f.serviceSnapshot.get("capacityKg")).thenReturn(5.0);
            }
            f.runTransaction();
            ShopOrderService service = new ShopOrderService(f.firebase);
            ShopOrderService.CreateOrderRequest req = new ShopOrderService.CreateOrderRequest(
                    "Customer", "+639170000000", "svc-A", c.qty(), c.actualWeight(), List.of(),
                    "", null, "CASH", "PAY_AT_CLAIM", "00000000-0000-4000-8000-000000000001");
            ShopOrderService.OrderSummary created = service.create(f.staff(), req);

            assertEquals(c.billingType(), created.billingType());
            assertEquals(c.unit(), created.unit());
            assertEquals(0, created.quantity().compareTo(c.qty()));
            assertEquals(new BigDecimal(c.expectedTotal()), created.totalPrice());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(f.transaction).create(eq(f.orderRef), captor.capture());
            Map<String, Object> saved = captor.getValue();
            assertFirestoreSupported("root", saved);

            // Simulate reading that saved map back across staff, owner, customer, and public responses
            QueryDocumentSnapshot stored = mock(QueryDocumentSnapshot.class);
            when(stored.exists()).thenReturn(true);
            when(stored.getId()).thenReturn("order-id-new");
            when(stored.getString("shopId")).thenReturn("shop-A");
            when(stored.getString("orderCode")).thenReturn("O-000001");
            when(stored.getString("customerName")).thenReturn("Customer");
            when(stored.getString("mobile")).thenReturn("639170000000");
            when(stored.getString("customerUserId")).thenReturn("customer-1");
            when(stored.getString("serviceId")).thenReturn("svc-A");
            when(stored.getString("serviceName")).thenReturn("Wash");
            when(stored.getString("stage")).thenReturn("RECEIVED");
            when(stored.getString("paymentStatus")).thenReturn("PAID");
            when(stored.getString("paymentMethod")).thenReturn("CASH");
            when(stored.getString("quantityUnit")).thenReturn((String) saved.get("quantityUnit"));
            when(stored.getString("unit")).thenReturn((String) saved.get("unit"));
            when(stored.get("quantity")).thenReturn(saved.get("quantity"));
            when(stored.get("kilos")).thenReturn(saved.get("kilos"));
            when(stored.get("actualWeightKg")).thenReturn(saved.get("actualWeightKg"));
            when(stored.get("service")).thenReturn(saved.get("service"));
            when(stored.get("totalPriceCentavos")).thenReturn(saved.get("totalPriceCentavos"));
            when(stored.get("serviceSubtotal")).thenReturn(saved.get("serviceSubtotal"));
            when(stored.get("addOnsSubtotal")).thenReturn(saved.get("addOnsSubtotal"));
            when(stored.get("addOns")).thenReturn(saved.get("addOns"));

            Query shopOrdersQuery = mock(Query.class);
            QuerySnapshot shopOrdersSnap = mock(QuerySnapshot.class);
            when(f.orders.whereEqualTo("shopId", "shop-A")).thenReturn(shopOrdersQuery);
            when(shopOrdersQuery.get()).thenReturn(ApiFutures.immediateFuture(shopOrdersSnap));
            when(shopOrdersSnap.getDocuments()).thenReturn(List.of(stored));

            ShopOrderService.OrderSummary staffRow = service.list(f.staff(), null, null, 0).content().get(0);
            ShopOrderService.OrderSummary ownerRow = service.list(f.owner(), null, null, 0).content().get(0);
            assertEquals(0, staffRow.quantity().compareTo(c.qty()));
            assertEquals(c.unit(), staffRow.unit());
            assertEquals(c.billingType(), staffRow.billingType());
            assertEquals(0, ownerRow.quantity().compareTo(c.qty()));
            assertEquals(c.unit(), ownerRow.unit());

            DocumentReference storedRef = mock(DocumentReference.class);
            CollectionReference priceLogCol = mock(CollectionReference.class);
            QuerySnapshot priceLogSnap = mock(QuerySnapshot.class);
            when(f.orders.document("order-id-new")).thenReturn(storedRef);
            when(storedRef.get()).thenReturn(ApiFutures.immediateFuture(stored));
            when(storedRef.collection("priceLog")).thenReturn(priceLogCol);
            when(priceLogCol.get()).thenReturn(ApiFutures.immediateFuture(priceLogSnap));
            when(priceLogSnap.getDocuments()).thenReturn(List.of());
            stubHistoryAndPayments(f, "shop-A", "order-id-new");

            ShopOrderService.OrderDetail staffDetail = service.get(f.staff(), "order-id-new");
            ShopOrderService.OrderDetail ownerDetail = service.get(f.owner(), "order-id-new");
            assertEquals(0, staffDetail.order().quantity().compareTo(c.qty()));
            assertEquals(c.unit(), staffDetail.order().quantityUnit());
            assertEquals(c.billingType(), staffDetail.invoice().service().billingType());
            assertEquals(0, ownerDetail.order().quantity().compareTo(c.qty()));
            assertEquals(c.unit(), ownerDetail.order().quantityUnit());
            assertNotNull(ownerDetail.invoice());

            Query custQuery = mock(Query.class);
            QuerySnapshot custSnap = mock(QuerySnapshot.class);
            when(f.orders.whereEqualTo("customerUserId", "customer-1")).thenReturn(custQuery);
            when(custQuery.get()).thenReturn(ApiFutures.immediateFuture(custSnap));
            when(custSnap.getDocuments()).thenReturn(List.of(stored));
            ShopOrderService.CustomerOrderSummary custRow = service.listMyOrders(f.customer(), 0).content().get(0);
            assertEquals(0, custRow.quantity().compareTo(c.qty()));
            assertEquals(c.unit(), custRow.unit());
            assertEquals(c.billingType(), custRow.billingType());

            ShopOrderService.CustomerOrderDetail custDetail = service.getMyOrder(f.customer(), "order-id-new");
            assertEquals(0, custDetail.order().quantity().compareTo(c.qty()));
            assertEquals(c.unit(), custDetail.order().quantityUnit());
            assertEquals(c.billingType(), custDetail.order().billingType());
            assertNotNull(custDetail.invoice());
            assertEquals(0, custDetail.invoice().quantity().compareTo(c.qty()));
            assertEquals(c.unit(), custDetail.invoice().quantityUnit());

            RegistrationRateLimitService limits = mock(RegistrationRateLimitService.class);
            Query pubQuery = mock(Query.class);
            QuerySnapshot pubSnap = mock(QuerySnapshot.class);
            when(f.orders.whereEqualTo("orderCode", "O-000001")).thenReturn(pubQuery);
            when(pubQuery.get()).thenReturn(ApiFutures.immediateFuture(pubSnap));
            when(pubSnap.getDocuments()).thenReturn(List.of(stored));
            PublicOrderLookupService.WalkInOrder pubRow = new PublicOrderLookupService(f.firebase, limits)
                    .lookup("O-000001", "639170000000", "198.51.100.10");
            assertEquals(0, pubRow.quantity().compareTo(c.qty()));
            assertEquals(c.unit(), pubRow.quantityUnit());
            assertEquals(c.billingType(), pubRow.billingType());
        }
    }

    private static void assertFirestoreSupported(String path, Object value) {
        if (value == null) return;
        assertFalse(value instanceof BigDecimal, path + " must not be BigDecimal");
        if (value instanceof String || value instanceof Boolean || value instanceof Double
                || value instanceof Long || value instanceof Integer || value instanceof FieldValue) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                assertFirestoreSupported(path + "." + entry.getKey(), entry.getValue());
            }
            return;
        }
        if (value instanceof Collection<?> list) {
            int i = 0;
            for (Object item : list) {
                assertFirestoreSupported(path + "[" + i++ + "]", item);
            }
            return;
        }
        fail("Unsupported Firestore type at " + path + ": " + value.getClass().getName());
    }

    @Test
    void customerGetsNoInvoiceWhenOrderIsUnpaidAndGetsInvoiceWhenPaid() throws Exception {
        Fixture f = new Fixture();
        DocumentReference orderRef = mock(DocumentReference.class);
        DocumentSnapshot order = mock(DocumentSnapshot.class);
        when(f.orders.document("order-1")).thenReturn(orderRef);
        when(orderRef.get()).thenReturn(ApiFutures.immediateFuture(order));
        when(order.exists()).thenReturn(true);
        when(order.getId()).thenReturn("order-1");
        when(order.getString("customerUserId")).thenReturn("customer-1");
        when(order.getString("shopId")).thenReturn("shop-A");
        when(order.getString("orderCode")).thenReturn("O-000001");
        when(order.getString("customerName")).thenReturn("Test Customer");
        when(order.getString("serviceName")).thenReturn("Wash");
        when(order.getString("stage")).thenReturn("READY");
        when(order.getString("paymentStatus")).thenReturn("UNPAID");
        when(order.getString("paymentMethodPreference")).thenReturn("CASH");
        when(order.getString("paymentTiming")).thenReturn("PAY_NOW");
        when(order.getString("unit")).thenReturn("kg");
        when(order.getString("quantityUnit")).thenReturn("kg");
        when(order.get("quantity")).thenReturn(2.0);
        when(order.get("totalPriceCentavos")).thenReturn(5000L);
        when(order.get("serviceSubtotal")).thenReturn(50.0);
        when(order.get("addOnsSubtotal")).thenReturn(0.0);
        when(order.get("addOns")).thenReturn(List.of());
        when(order.get("service")).thenReturn(Map.of("id", "svc", "name", "Wash",
                "category", "Wash", "billingType", "PER_KG", "unitPrice", 25.0));
        stubHistoryAndPayments(f, "shop-A", "order-1");

        ShopOrderService.CustomerOrderDetail unpaidResult =
                new ShopOrderService(f.firebase).getMyOrder(f.customer(), "order-1");
        assertNull(unpaidResult.invoice(), "Customer must NOT see invoice when order is UNPAID");

        when(order.getString("paymentStatus")).thenReturn("PAID");
        when(order.getString("paymentMethod")).thenReturn("CASH");
        when(f.shop.getString("name")).thenReturn("Test Shop");
        ShopOrderService.CustomerOrderDetail paidResult =
                new ShopOrderService(f.firebase).getMyOrder(f.customer(), "order-1");
        assertNotNull(paidResult.invoice(), "Customer MUST see invoice when order is PAID");
        assertEquals("Test Shop", paidResult.invoice().shopName());
    }

    @Test
    void customerCannotReadAnotherCustomersOrder() throws Exception {
        Fixture f = new Fixture();
        DocumentReference orderRef = mock(DocumentReference.class);
        DocumentSnapshot order = mock(DocumentSnapshot.class);
        when(f.orders.document("order-foreign")).thenReturn(orderRef);
        when(orderRef.get()).thenReturn(ApiFutures.immediateFuture(order));
        when(order.exists()).thenReturn(true);
        when(order.getString("customerUserId")).thenReturn("other-customer");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).getMyOrder(f.customer(), "order-foreign"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    private static void stubHistoryAndPayments(Fixture f, String shopId, String orderId) throws Exception {
        CollectionReference histories = mock(CollectionReference.class);
        CollectionReference payments = mock(CollectionReference.class);
        Query histShop = mock(Query.class); Query histOrder = mock(Query.class);
        QuerySnapshot histRows = mock(QuerySnapshot.class);
        Query payShop = mock(Query.class); Query payOrder = mock(Query.class);
        QuerySnapshot payRows = mock(QuerySnapshot.class);
        when(f.db.collection("orderStageHistory")).thenReturn(histories);
        when(f.db.collection("payments")).thenReturn(payments);
        when(histories.whereEqualTo("shopId", shopId)).thenReturn(histShop);
        when(histShop.whereEqualTo("orderId", orderId)).thenReturn(histOrder);
        when(histOrder.get()).thenReturn(ApiFutures.immediateFuture(histRows));
        when(histRows.getDocuments()).thenReturn(List.of());
        when(payments.whereEqualTo("shopId", shopId)).thenReturn(payShop);
        when(payShop.whereEqualTo("orderId", orderId)).thenReturn(payOrder);
        when(payOrder.get()).thenReturn(ApiFutures.immediateFuture(payRows));
        when(payRows.getDocuments()).thenReturn(List.of());
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference shops = mock(CollectionReference.class);
        final CollectionReference services = mock(CollectionReference.class);
        final CollectionReference counters = mock(CollectionReference.class);
        final CollectionReference orders = mock(CollectionReference.class);
        final CollectionReference createRequests = mock(CollectionReference.class);
        final CollectionReference history = mock(CollectionReference.class);
        final DocumentReference shopRef = mock(DocumentReference.class);
        final DocumentReference serviceRef = mock(DocumentReference.class);
        final DocumentReference counterRef = mock(DocumentReference.class);
        final DocumentReference orderRef = mock(DocumentReference.class);
        final DocumentReference historyRef = mock(DocumentReference.class);
        final DocumentReference createRequestRef = mock(DocumentReference.class);
        final DocumentReference priceLogRef = mock(DocumentReference.class);
        final CollectionReference priceLogCollection = mock(CollectionReference.class);
        final DocumentSnapshot shopSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot shop = mock(DocumentSnapshot.class);
        final DocumentSnapshot serviceSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot counterSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot createRequestSnapshot = mock(DocumentSnapshot.class);
        final Transaction transaction = mock(Transaction.class);

        Fixture() throws Exception {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shops")).thenReturn(shops);
            when(db.collection("services")).thenReturn(services);
            when(db.collection("orderCounters")).thenReturn(counters);
            when(db.collection("orders")).thenReturn(orders);
            when(shops.document("shop-A")).thenReturn(shopRef);
            when(shopRef.collection("orderCreateRequests")).thenReturn(createRequests);
            when(createRequests.document(any(String.class))).thenReturn(createRequestRef);
            when(services.document("svc-A")).thenReturn(serviceRef);
            when(counters.document("shop-A")).thenReturn(counterRef);
            when(orders.document()).thenReturn(orderRef);
            when(orderRef.getId()).thenReturn("order-id-new");
            when(orderRef.collection("priceLog")).thenReturn(priceLogCollection);
            when(priceLogCollection.document()).thenReturn(priceLogRef);
            when(db.collection("orderStageHistory")).thenReturn(history);
            when(history.document()).thenReturn(historyRef);
            when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
            when(shop.exists()).thenReturn(true);
            when(shop.getString("status")).thenReturn("ACTIVE");
            when(shop.getString("name")).thenReturn("Shop A");
            when(shopSnapshot.exists()).thenReturn(true);
            when(shopSnapshot.getString("status")).thenReturn("ACTIVE");
            when(serviceSnapshot.exists()).thenReturn(true);
            when(serviceSnapshot.getString("shopId")).thenReturn("shop-A");
            when(serviceSnapshot.getBoolean("active")).thenReturn(true);
            when(serviceSnapshot.getString("name")).thenReturn("Wash");
            when(serviceSnapshot.getString("unit")).thenReturn("kg");
            when(serviceSnapshot.get("pricePerUnit")).thenReturn(15.0);
            when(counterSnapshot.exists()).thenReturn(false);
        }

        @SuppressWarnings("unchecked")
        void runTransaction() throws Exception {
            when(transaction.get(any(DocumentReference.class))).thenAnswer(invocation -> {
                DocumentReference ref = invocation.getArgument(0);
                if (ref == shopRef) return ApiFutures.immediateFuture(shopSnapshot);
                if (ref == createRequestRef) return ApiFutures.immediateFuture(createRequestSnapshot);
                if (ref == counterRef) return ApiFutures.immediateFuture(counterSnapshot);
                if (ref == serviceRef) return ApiFutures.immediateFuture(serviceSnapshot);
                return ApiFutures.immediateFuture(mock(DocumentSnapshot.class));
            });
            when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
                Transaction.Function<Object> work = invocation.getArgument(0);
                try { return ApiFutures.immediateFuture(work.updateCallback(transaction)); }
                catch (Exception e) { return ApiFutures.immediateFailedFuture(e); }
            });
        }

        FirebasePrincipal staff() { return new FirebasePrincipal("staff-uid", "s@test.com", "Staff", "staff-id", "STAFF", "shop-A", "ACTIVE"); }
        FirebasePrincipal owner() { return new FirebasePrincipal("owner-uid", "o@test.com", "Owner", "owner-id", "OWNER", "shop-A", "ACTIVE"); }
        FirebasePrincipal customer() { return new FirebasePrincipal("cust-uid", "c@test.com", "Test", "customer-1", "CUSTOMER", null, null); }
    }
}
