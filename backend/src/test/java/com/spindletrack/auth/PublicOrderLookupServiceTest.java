package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublicOrderLookupServiceTest {
    @Test
    void orderWithNoKilosFieldReturnsNullQuantityInPublicResponse() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        RegistrationRateLimitService limits = mock(RegistrationRateLimitService.class);
        Firestore db = mock(Firestore.class);
        CollectionReference orders = mock(CollectionReference.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference history = mock(CollectionReference.class);
        Query orderQuery = mock(Query.class);
        Query historyQuery = mock(Query.class);
        QuerySnapshot orderRows = mock(QuerySnapshot.class);
        QuerySnapshot stageRows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot order = mock(QueryDocumentSnapshot.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        DocumentReference shopRef = mock(DocumentReference.class);

        when(firebase.firestore()).thenReturn(db);
        when(db.collection("orders")).thenReturn(orders);
        when(orders.whereEqualTo("orderCode", "O-000999")).thenReturn(orderQuery);
        when(orderQuery.get()).thenReturn(ApiFutures.immediateFuture(orderRows));
        when(orderRows.getDocuments()).thenReturn(List.of(order));
        when(order.getId()).thenReturn("old-order-id");
        when(order.getString("mobile")).thenReturn("639171234567");
        when(order.getString("shopId")).thenReturn("shop-string-1");
        when(order.getString("stage")).thenReturn("RECEIVED");
        when(order.getString("paymentStatus")).thenReturn("UNPAID");
        when(order.get("kilos")).thenReturn(null);
        when(order.get("quantity")).thenReturn(null);

        when(db.collection("shops")).thenReturn(shops);
        when(shops.document("shop-string-1")).thenReturn(shopRef);
        when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
        when(shop.exists()).thenReturn(true);
        when(shop.getString("status")).thenReturn("ACTIVE");

        when(db.collection("orderStageHistory")).thenReturn(history);
        when(history.whereEqualTo("shopId", "shop-string-1")).thenReturn(historyQuery);
        when(historyQuery.whereEqualTo("orderId", "old-order-id")).thenReturn(historyQuery);
        when(historyQuery.get()).thenReturn(ApiFutures.immediateFuture(stageRows));
        when(stageRows.getDocuments()).thenReturn(List.of());

        PublicOrderLookupService.WalkInOrder result = new PublicOrderLookupService(firebase, limits)
                .lookup("O-000999", "639171234567", "198.51.100.9");

        assertEquals(null, result.quantity(), "Legacy order with no kilos/quantity must return null, not 0.00");
    }

    @Test
    void matchingCodeAndMobileReturnsOnlyMaskedTrackingData() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        RegistrationRateLimitService limits = mock(RegistrationRateLimitService.class);
        Firestore db = mock(Firestore.class);
        CollectionReference orders = mock(CollectionReference.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference history = mock(CollectionReference.class);
        Query orderQuery = mock(Query.class);
        Query historyQuery = mock(Query.class);
        QuerySnapshot orderRows = mock(QuerySnapshot.class);
        QuerySnapshot stageRows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot order = mock(QueryDocumentSnapshot.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        DocumentReference shopRef = mock(DocumentReference.class);

        when(firebase.firestore()).thenReturn(db);
        when(db.collection("orders")).thenReturn(orders);
        when(orders.whereEqualTo("orderCode", "O-000123")).thenReturn(orderQuery);
        when(orderQuery.get()).thenReturn(ApiFutures.immediateFuture(orderRows));
        when(orderRows.getDocuments()).thenReturn(List.of(order));
        when(order.getId()).thenReturn("order-string-1");
        when(order.getString("mobile")).thenReturn("639171234567");
        when(order.getString("shopId")).thenReturn("shop-string-1");
        when(order.getString("stage")).thenReturn("READY");
        when(order.getString("paymentStatus")).thenReturn("PENDING");
        when(order.get("quantity")).thenReturn(new java.math.BigDecimal("2.50"));
        when(order.getString("unit")).thenReturn("piece");

        when(db.collection("shops")).thenReturn(shops);
        when(shops.document("shop-string-1")).thenReturn(shopRef);
        when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
        when(shop.exists()).thenReturn(true);
        when(shop.getString("status")).thenReturn("ACTIVE");

        when(db.collection("orderStageHistory")).thenReturn(history);
        when(history.whereEqualTo("shopId", "shop-string-1")).thenReturn(historyQuery);
        when(historyQuery.whereEqualTo("orderId", "order-string-1")).thenReturn(historyQuery);
        when(historyQuery.get()).thenReturn(ApiFutures.immediateFuture(stageRows));
        when(stageRows.getDocuments()).thenReturn(List.of());

        PublicOrderLookupService.WalkInOrder result = new PublicOrderLookupService(firebase, limits)
                .lookup(" o-000123 ", "+63 917 123 4567", "198.51.100.9");

        assertEquals("O-000123", result.orderCode());
        assertEquals("********4567", result.maskedMobile());
        assertEquals(com.spindletrack.LaundryStage.READY, result.currentStage());
        assertEquals("PENDING", result.paymentStatus());
        assertEquals(new java.math.BigDecimal("2.50"), result.quantity());
        assertEquals("piece", result.unit());
        assertTrue(result.stageHistory().isEmpty());
        verify(db, never()).collection("payments");
        verify(limits).checkPublicOrderLookupLimit("198.51.100.9");
        // A successful lookup must NOT increment the failure counter
        verify(limits, org.mockito.Mockito.never()).recordPublicOrderLookupFailure(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void wrongMobileAndMalformedInputUseTheSameGenericNotFoundFailure() throws Exception {
        Fixture f = new Fixture();
        QueryDocumentSnapshot order = mock(QueryDocumentSnapshot.class);
        when(order.getString("mobile")).thenReturn("639171234567");
        when(f.rows.getDocuments()).thenReturn(List.of(order));
        f.setupOrderQuery();
        PublicOrderLookupService service = new PublicOrderLookupService(f.firebase, f.limits);

        ResponseStatusException wrong = assertThrows(ResponseStatusException.class,
                () -> service.lookup("O-000123", "639171999999", "198.51.100.9"));
        ResponseStatusException malformed = assertThrows(ResponseStatusException.class,
                () -> service.lookup("bad code", "639171999999", "198.51.100.9"));
        assertEquals(HttpStatus.NOT_FOUND, wrong.getStatusCode());
        assertEquals(wrong.getReason(), malformed.getReason());
        verify(f.db, never()).collection("shops");
    }

    @Test
    void duplicateCrossShopMatchesAndInactiveShopUseGenericNotFound() throws Exception {
        Fixture duplicate = new Fixture();
        QueryDocumentSnapshot first = mock(QueryDocumentSnapshot.class);
        QueryDocumentSnapshot second = mock(QueryDocumentSnapshot.class);
        when(first.getString("mobile")).thenReturn("639171234567");
        when(second.getString("mobile")).thenReturn("639171234567");
        when(duplicate.rows.getDocuments()).thenReturn(List.of(first, second));
        ResponseStatusException ambiguous = assertThrows(ResponseStatusException.class,
                () -> new PublicOrderLookupService(duplicate.firebase, duplicate.limits)
                        .lookup("O-000123", "639171234567", "198.51.100.9"));
        assertEquals(HttpStatus.NOT_FOUND, ambiguous.getStatusCode());
        verify(duplicate.db, never()).collection("shops");

        Fixture inactive = new Fixture();
        QueryDocumentSnapshot order = mock(QueryDocumentSnapshot.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        when(inactive.rows.getDocuments()).thenReturn(List.of(order));
        when(order.getString("mobile")).thenReturn("639171234567");
        when(order.getString("shopId")).thenReturn("shop-string-1");
        when(order.getId()).thenReturn("order-string-1");
        when(inactive.db.collection("shops")).thenReturn(mock(CollectionReference.class));
        CollectionReference shops = inactive.db.collection("shops");
        when(shops.document("shop-string-1")).thenReturn(shopRef);
        when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
        when(shop.exists()).thenReturn(true);
        when(shop.getString("status")).thenReturn("DEACTIVATED");
        ResponseStatusException unavailable = assertThrows(ResponseStatusException.class,
                () -> new PublicOrderLookupService(inactive.firebase, inactive.limits)
                        .lookup("O-000123", "639171234567", "198.51.100.9"));
        assertEquals(ambiguous.getReason(), unavailable.getReason());
        verify(inactive.db, never()).collection("orderStageHistory");
    }

    @Test
    void publicPayPalRejectsWrongMobileWithGenericNotFoundAndTracksRateLimit() throws Exception {
        Fixture f = new Fixture();
        QueryDocumentSnapshot order = mock(QueryDocumentSnapshot.class);
        when(order.getString("mobile")).thenReturn("639171234567");
        when(f.rows.getDocuments()).thenReturn(List.of(order));
        ShopOrderService shopOrders = mock(ShopOrderService.class);
        PublicOrderLookupService service = new PublicOrderLookupService(f.firebase, f.limits, shopOrders);

        ResponseStatusException createErr = assertThrows(ResponseStatusException.class,
                () -> service.createPublicPayPalOrder(
                        new PublicOrderLookupService.PublicPayPalOrderRequest("O-000123", "639171999999", null),
                        "198.51.100.9"));
        ResponseStatusException captureErr = assertThrows(ResponseStatusException.class,
                () -> service.capturePublicPayPalOrder(
                        new PublicOrderLookupService.PublicPayPalCaptureRequest("O-000123", "639171999999", "PP-1"),
                        "198.51.100.9"));

        assertEquals(HttpStatus.NOT_FOUND, createErr.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, captureErr.getStatusCode());
        assertEquals("Order could not be found. Check the order code and mobile number.", createErr.getReason());
        assertEquals(createErr.getReason(), captureErr.getReason());
        verify(f.limits, times(2)).recordPublicOrderLookupFailure("198.51.100.9");
        verify(shopOrders, never()).createPublicPayPalOrder(anyString(), any());
        verify(shopOrders, never()).capturePublicPayPalOrder(anyString(), any());
    }

    @Test
    void publicPayPalRespectsRateLimitingBeforeDatabaseLookup() {
        Fixture f = mockFixtureSafely();
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many order lookup attempts. Please wait 15 minutes and try again."))
                .when(f.limits).checkPublicOrderLookupLimit("198.51.100.99");
        ShopOrderService shopOrders = mock(ShopOrderService.class);
        PublicOrderLookupService service = new PublicOrderLookupService(f.firebase, f.limits, shopOrders);

        ResponseStatusException createLimited = assertThrows(ResponseStatusException.class,
                () -> service.createPublicPayPalOrder(
                        new PublicOrderLookupService.PublicPayPalOrderRequest("O-000123", "639171234567", null),
                        "198.51.100.99"));
        ResponseStatusException captureLimited = assertThrows(ResponseStatusException.class,
                () -> service.capturePublicPayPalOrder(
                        new PublicOrderLookupService.PublicPayPalCaptureRequest("O-000123", "639171234567", "PP-1"),
                        "198.51.100.99"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, createLimited.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, captureLimited.getStatusCode());
        verify(f.db, never()).collection(anyString());
        verify(shopOrders, never()).createPublicPayPalOrder(anyString(), any());
    }

    @Test
    void publicPayPalCreateAndCaptureVerifyMatchWithoutIncrementingFailures() throws Exception {
        Fixture f = new Fixture();
        QueryDocumentSnapshot order = mock(QueryDocumentSnapshot.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        CollectionReference shops = mock(CollectionReference.class);
        when(f.rows.getDocuments()).thenReturn(List.of(order));
        when(order.getId()).thenReturn("order-walkin-1");
        when(order.getString("mobile")).thenReturn("639171234567");
        when(order.getString("shopId")).thenReturn("shop-string-1");
        when(f.db.collection("shops")).thenReturn(shops);
        when(shops.document("shop-string-1")).thenReturn(shopRef);
        when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
        when(shop.exists()).thenReturn(true);
        when(shop.getString("status")).thenReturn("ACTIVE");

        ShopOrderService shopOrders = mock(ShopOrderService.class);
        ShopOrderService.OrderSummary pendingSummary = new ShopOrderService.OrderSummary(
                "order-walkin-1", "shop-string-1", "O-000123", "Walk In", "639171234567",
                null, "svc-1", "Wash", new java.math.BigDecimal("2.00"), new java.math.BigDecimal("100.00"),
                "", com.spindletrack.LaundryStage.RECEIVED, java.time.Instant.now(), "staff-1",
                "PENDING", "PAYPAL", null, null, null, "PP-PUB-99",
                "https://www.sandbox.paypal.com/checkoutnow?token=PP-PUB-99", 10000L, "order-walkin-1");
        when(shopOrders.createPublicPayPalOrder(eq("order-walkin-1"), any()))
                .thenReturn(new ShopOrderService.PayPalOrderResult(pendingSummary, "PP-PUB-99",
                        "https://www.sandbox.paypal.com/checkoutnow?token=PP-PUB-99"));
        when(shopOrders.capturePublicPayPalOrder(eq("order-walkin-1"), any()))
                .thenReturn(pendingSummary);

        PublicOrderLookupService service = new PublicOrderLookupService(f.firebase, f.limits, shopOrders);
        PublicOrderLookupService.PublicPayPalOrderResponse created = service.createPublicPayPalOrder(
                new PublicOrderLookupService.PublicPayPalOrderRequest("O-000123", "+63 917 123 4567", null),
                "198.51.100.9");
        PublicOrderLookupService.PublicPayPalCaptureResponse incomplete = service.capturePublicPayPalOrder(
                new PublicOrderLookupService.PublicPayPalCaptureRequest("O-000123", "639171234567", "PP-PUB-99"),
                "198.51.100.9");

        assertEquals("PP-PUB-99", created.paypalOrderId());
        assertEquals("PENDING", incomplete.paymentStatus());
        verify(f.limits, never()).recordPublicOrderLookupFailure(anyString());
    }

    private static Fixture mockFixtureSafely() {
        try { return new Fixture(); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final RegistrationRateLimitService limits = mock(RegistrationRateLimitService.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference orders = mock(CollectionReference.class);
        final Query query = mock(Query.class);
        final QuerySnapshot rows = mock(QuerySnapshot.class);

        Fixture() throws Exception {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("orders")).thenReturn(orders);
            when(orders.whereEqualTo("orderCode", "O-000123")).thenReturn(query);
            when(query.get()).thenReturn(ApiFutures.immediateFuture(rows));
        }

        void setupOrderQuery() { }
    }
}
