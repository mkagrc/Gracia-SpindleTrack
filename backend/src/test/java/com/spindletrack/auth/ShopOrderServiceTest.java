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
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;
import com.spindletrack.LaundryStage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopOrderServiceTest {
    @Test
    void createsOrderUsingStoredServicePriceAndIgnoresClientSuppliedTotal() throws Exception {
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
        assertEquals("kg", first.unit());
        assertEquals(new BigDecimal("35.25"), first.totalPrice());
        assertEquals(LaundryStage.RECEIVED, first.stage());
        verify(f.transaction).create(eq(f.orderRef), argThat(values -> !values.containsKey("items")
                && new BigDecimal(values.get("total").toString()).compareTo(new BigDecimal("35.25")) == 0
                && Long.valueOf(3525L).equals(values.get("totalPriceCentavos"))
                && values.get("quantity") instanceof Number qtyNum && !(values.get("quantity") instanceof BigDecimal)
                && new BigDecimal(qtyNum.toString()).compareTo(new BigDecimal("2.35")) == 0
                && "kg".equals(values.get("unit"))));
        verify(f.transaction).create(eq(f.priceLogRef), any());
    }

    @Test
    void orderUnitFollowsServiceForWholePieceAndLoadQuantities() throws Exception {
        for (String unit : List.of("piece", "load")) {
            Fixture f = new Fixture();
            String billingType = "piece".equals(unit) ? "PER_PIECE" : "PER_LOAD";
            when(f.serviceSnapshot.getString("unit")).thenReturn(unit);
            f.runTransaction();
            ShopOrderService.CreateOrderRequest request = new ShopOrderService.CreateOrderRequest("  A Person  ",
                    "+63 (917) 123-4567", "svc-string-A", new BigDecimal("2"), new BigDecimal("30.75"), "");
            ShopOrderService.OrderSummary result = new ShopOrderService(f.firebase).create(f.staff(), request);
            assertEquals(unit, result.unit());
            assertEquals(new BigDecimal("30.00"), result.totalPrice());
            verify(f.transaction).create(eq(f.orderRef), argThat(values -> unit.equals(values.get("quantityUnit"))
                    && values.get("quantity") instanceof Number quantity
                    && new BigDecimal(quantity.toString()).compareTo(new BigDecimal("2.00")) == 0
                    && values.get("service") instanceof Map<?, ?> service
                    && billingType.equals(service.get("billingType"))
                    && values.get("serviceSubtotal") instanceof Number serviceSubtotal
                    && new BigDecimal(serviceSubtotal.toString()).compareTo(new BigDecimal("30.00")) == 0
                    && values.get("addOnsSubtotal") instanceof Number addOnsSubtotal
                    && new BigDecimal(addOnsSubtotal.toString()).compareTo(BigDecimal.ZERO) == 0
                    && values.get("total") instanceof Number total
                    && new BigDecimal(total.toString()).compareTo(new BigDecimal("30.00")) == 0));
        }
    }

    @Test
    void addOnStockDeductionSharesOrderTransactionAndDuplicateRequestDoesNotDeductAgain() throws Exception {
        Fixture f = new Fixture();
        f.stubTrackedAddOn(10, 1);
        f.runTransaction();
        ShopOrderService.CreateOrderRequest request = f.requestWithAddOn("00000000-0000-4000-8000-000000000099", 2);
        ShopOrderService service = new ShopOrderService(f.firebase);
        ShopOrderService.OrderSummary created = service.create(f.staff(), request);
        assertEquals(new BigDecimal("40.25"), created.totalPrice());
        verify(f.transaction).update(eq(f.addOnRef), argThat(values -> Integer.valueOf(8).equals(values.get("stockOnHand"))));

        org.mockito.ArgumentCaptor<Map<String, Object>> requestValues = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(f.transaction).create(eq(f.createRequestRef), requestValues.capture());
        when(f.createRequestSnapshot.exists()).thenReturn(true);
        when(f.createRequestSnapshot.getString("requestFingerprint")).thenReturn((String) requestValues.getValue().get("requestFingerprint"));
        when(f.createRequestSnapshot.getString("orderId")).thenReturn("order-id-A");
        when(f.orders.document("order-id-A")).thenReturn(f.orderRef);
        f.stubOwnedOrder("order-id-A", "shop-string-A", "RECEIVED");

        ShopOrderService.OrderSummary replay = service.create(f.staff(), request);
        assertEquals("order-id-A", replay.id());
        verify(f.transaction, org.mockito.Mockito.times(1)).update(eq(f.addOnRef), any(Map.class));
        verify(f.transaction, org.mockito.Mockito.times(1)).create(eq(f.orderRef), any(Map.class));
    }

    @Test
    void insufficientAddOnStockReturnsConflictWithoutAnyTransactionWrites() throws Exception {
        Fixture f = new Fixture();
        f.stubTrackedAddOn(1, 1);
        f.runTransaction();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.staff(), f.requestWithAddOn(
                        "00000000-0000-4000-8000-000000000098", 2)));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(f.transaction, never()).update(any(DocumentReference.class), any(Map.class));
        verify(f.transaction, never()).set(any(DocumentReference.class), any(Map.class));
        verify(f.transaction, never()).create(any(DocumentReference.class), any());
    }

    @Test
    void legacyKilosOnlyOrderIsReturnedWithKgUnit() throws Exception {
        Fixture f = new Fixture();
        Query query = mock(Query.class);
        QuerySnapshot rows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot legacy = mock(QueryDocumentSnapshot.class);
        when(f.orders.whereEqualTo("shopId", "shop-string-A")).thenReturn(query);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(rows));
        when(rows.getDocuments()).thenReturn(List.of(legacy));
        when(legacy.getId()).thenReturn("legacy-order-1");
        when(legacy.getString("shopId")).thenReturn("shop-string-A");
        when(legacy.getString("orderCode")).thenReturn("O-000001");
        when(legacy.getString("customerName")).thenReturn("Legacy customer");
        when(legacy.getString("stage")).thenReturn("READY");
        when(legacy.get("kilos")).thenReturn(new BigDecimal("4.25"));
        when(legacy.get("totalPrice")).thenReturn(new BigDecimal("50.00"));

        ShopOrderService.OrderSummary result = new ShopOrderService(f.firebase).list(f.owner(), null, null, 0).content().get(0);
        assertEquals(new BigDecimal("4.25"), result.quantity());
        assertEquals("kg", result.unit());
    }

    @Test
    void orderWithNoKilosOrQuantityFieldReturnsNullWeight() throws Exception {
        Fixture f = new Fixture();
        Query query = mock(Query.class);
        QuerySnapshot rows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot noWeight = mock(QueryDocumentSnapshot.class);
        when(f.orders.whereEqualTo("shopId", "shop-string-A")).thenReturn(query);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(rows));
        when(rows.getDocuments()).thenReturn(List.of(noWeight));
        when(noWeight.getId()).thenReturn("old-order-1");
        when(noWeight.getString("shopId")).thenReturn("shop-string-A");
        when(noWeight.getString("orderCode")).thenReturn("O-000002");
        when(noWeight.getString("customerName")).thenReturn("Old customer");
        when(noWeight.getString("stage")).thenReturn("RECEIVED");
        when(noWeight.get("kilos")).thenReturn(null);
        when(noWeight.get("quantity")).thenReturn(null);
        when(noWeight.get("totalPriceCentavos")).thenReturn(5000L);

        ShopOrderService.OrderSummary result = new ShopOrderService(f.firebase).list(f.owner(), null, null, 0).content().get(0);
        assertEquals(null, result.kilos(),
                "An order with no kilos or quantity field must return null weight, not 0.00");
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
        ShopOrderService service = new ShopOrderService(f.firebase);
        ShopOrderService.OrderSummary moved = service.advance(f.staff(), "order-id-X",
                new ShopOrderService.ExpectedStageRequest("RECEIVED", "00000000-0000-4000-8000-000000000005"));
        assertEquals(LaundryStage.WASHING, moved.stage());
        verify(f.transaction).update(eq(f.orderRef), argThat(values -> "WASHING".equals(values.get("stage"))));
        verify(f.transaction).create(eq(f.historyRef), argThat(values -> "Staff".equals(values.get("changedByName"))));
        ResponseStatusException stale = assertThrows(ResponseStatusException.class, () -> service.advance(f.staff(), "order-id-X",
                new ShopOrderService.ExpectedStageRequest("WASHING", "00000000-0000-4000-8000-000000000008")));
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        when(f.stageRequestSnapshot.exists()).thenReturn(true);
        when(f.stageRequestSnapshot.getString("expectedStage")).thenReturn("RECEIVED");
        when(f.stageRequestSnapshot.getString("resultStage")).thenReturn("WASHING");
        when(f.orderSnapshot.getString("stage")).thenReturn("WASHING");
        assertEquals(LaundryStage.WASHING, service.advance(f.staff(), "order-id-X",
                new ShopOrderService.ExpectedStageRequest("RECEIVED", "00000000-0000-4000-8000-000000000005")).stage());
        verify(f.transaction, org.mockito.Mockito.times(1)).create(eq(f.stageRequestRef), any());
        when(f.orderSnapshot.getString("stage")).thenReturn("CLAIMED");
        ResponseStatusException finalStage = assertThrows(ResponseStatusException.class,
                () -> service.advance(f.staff(), "order-id-X",
                        new ShopOrderService.ExpectedStageRequest("CLAIMED", "00000000-0000-4000-8000-000000000006")));
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
                () -> service.advance(f.owner(), "order-id-X",
                        new ShopOrderService.ExpectedStageRequest("RECEIVED", "00000000-0000-4000-8000-000000000007"))).getStatusCode());
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference shops = mock(CollectionReference.class);
        final CollectionReference services = mock(CollectionReference.class);
        final CollectionReference counters = mock(CollectionReference.class);
        final CollectionReference orders = mock(CollectionReference.class);
        final CollectionReference createRequests = mock(CollectionReference.class);
        final CollectionReference stageRequests = mock(CollectionReference.class);
        final CollectionReference history = mock(CollectionReference.class);
        final CollectionReference addOns = mock(CollectionReference.class);
        final DocumentReference shopRef = mock(DocumentReference.class);
        final DocumentReference serviceRef = mock(DocumentReference.class);
        final DocumentReference counterRef = mock(DocumentReference.class);
        final DocumentReference orderRef = mock(DocumentReference.class);
        final DocumentReference foreignOrder = mock(DocumentReference.class);
        final DocumentReference historyRef = mock(DocumentReference.class);
        final DocumentReference createRequestRef = mock(DocumentReference.class);
        final DocumentReference stageRequestRef = mock(DocumentReference.class);
        final DocumentReference addOnRef = mock(DocumentReference.class);
        final DocumentReference priceLogRef = mock(DocumentReference.class);
        final CollectionReference priceLogCollection = mock(CollectionReference.class);
        final DocumentSnapshot shopSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot serviceSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot counterSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot orderSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot stageRequestSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot createRequestSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot addOnSnapshot = mock(DocumentSnapshot.class);
        final DocumentSnapshot foreignOrderSnapshot = mock(DocumentSnapshot.class);
        final Transaction transaction = mock(Transaction.class);

        Fixture() throws Exception {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shops")).thenReturn(shops);
            when(db.collection("services")).thenReturn(services);
            when(db.collection("orderCounters")).thenReturn(counters);
            when(db.collection("orders")).thenReturn(orders);
            when(db.collection("orderStageHistory")).thenReturn(history);
            when(db.collection("addOns")).thenReturn(addOns);
            when(shops.document("shop-string-A")).thenReturn(shopRef);
            when(shopRef.collection("orderCreateRequests")).thenReturn(createRequests);
            when(createRequests.document(any(String.class))).thenReturn(createRequestRef);
            when(addOns.document("addon-A")).thenReturn(addOnRef);
            when(services.document("svc-string-A")).thenReturn(serviceRef);
            when(counters.document("shop-string-A")).thenReturn(counterRef);
            when(orders.document()).thenReturn(orderRef);
            when(orders.document("order-id-X")).thenReturn(orderRef);
            when(orders.document("foreign-order-id")).thenReturn(foreignOrder);
            when(orderRef.getId()).thenReturn("order-id-A");
            when(history.document()).thenReturn(historyRef);
            when(orderRef.collection("stageAdvanceRequests")).thenReturn(stageRequests);
            when(stageRequests.document(any(String.class))).thenReturn(stageRequestRef);
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
            when(serviceSnapshot.get("pricePerUnit")).thenReturn(new BigDecimal("15.00"));
        }

        ShopOrderService.CreateOrderRequest request() {
            return new ShopOrderService.CreateOrderRequest("  A Person  ", "+63 (917) 123-4567", "svc-string-A",
                    new BigDecimal("2.35"), new BigDecimal("30.75"), "");
        }
        ShopOrderService.CreateOrderRequest requestWithAddOn(String requestId, int addOnQuantity) {
            return new ShopOrderService.CreateOrderRequest("A Person", "+63 (917) 123-4567", "svc-string-A",
                    new BigDecimal("2.35"), null, List.of(new ShopOrderService.AddOnSelection("addon-A", addOnQuantity)),
                    "", null, "CASH", "PAY_AT_CLAIM", requestId);
        }
        void stubTrackedAddOn(int stock, int deductionPerUnit) {
            when(addOnSnapshot.exists()).thenReturn(true);
            when(addOnSnapshot.getString("shopId")).thenReturn("shop-string-A");
            when(addOnSnapshot.getString("itemName")).thenReturn("Detergent sachet");
            when(addOnSnapshot.getString("category")).thenReturn("Detergent");
            when(addOnSnapshot.getString("unit")).thenReturn("sachet");
            when(addOnSnapshot.get("addOnPriceCentavos")).thenReturn(250L);
            when(addOnSnapshot.getLong("stockOnHand")).thenReturn((long) stock);
            when(addOnSnapshot.getLong("stockDeductionPerUnit")).thenReturn((long) deductionPerUnit);
            when(addOnSnapshot.getBoolean("trackStock")).thenReturn(true);
            when(addOnSnapshot.getBoolean("active")).thenReturn(true);
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
                if (ref == createRequestRef) return ApiFutures.immediateFuture(createRequestSnapshot);
                if (ref == stageRequestRef) return ApiFutures.immediateFuture(stageRequestSnapshot);
                if (ref == counterRef) return ApiFutures.immediateFuture(counterSnapshot);
                if (ref == serviceRef) return ApiFutures.immediateFuture(serviceSnapshot);
                if (ref == addOnRef) return ApiFutures.immediateFuture(addOnSnapshot);
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
