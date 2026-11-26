package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopOrderPricingServiceTest {
    @Test
    void createUsesStoredCatalogPriceAndAppendsInitialPriceAndNamedStageEntries() throws Exception {
        Fixture f = new Fixture();
        f.transactionHarness();
        ShopOrderService service = new ShopOrderService(f.firebase);
        ShopOrderService.CreateOrderRequest request = new ShopOrderService.CreateOrderRequest("Customer", "639171234567",
                "service-A", new BigDecimal("2.35"), new BigDecimal("123.45"), "");

        ShopOrderService.OrderSummary created = service.create(f.staff(), request);

        assertEquals(new BigDecimal("2.35"), created.kilos());
        assertEquals(new BigDecimal("123.45"), created.totalPrice());
        assertEquals("Wash", created.serviceName());
        assertEquals("kg", created.unit());
        assertEquals("UNPAID", created.paymentStatus());
        assertEquals(null, created.paymentMethod());
        assertEquals(null, created.paidAt());
        assertEquals(null, created.paidByUid());
        assertEquals(null, created.paidByName());
        verify(f.tx).create(eq(f.order), argThat(values -> Long.valueOf(12345L).equals(values.get("totalPriceCentavos"))
                && "UNPAID".equals(values.get("paymentStatus")) && values.get("paymentMethod") == null
                && values.get("paidAt") == null && values.get("paidByUid") == null && values.get("paidByName") == null
                && !values.containsKey("items") && Long.valueOf(12345L).equals(values.get("totalPriceCentavos"))
                && new BigDecimal(values.get("total").toString()).compareTo(new BigDecimal("123.45")) == 0));
        verify(f.tx).create(eq(f.priceLog), argThat(values -> values.get("previousPrice") == null
                && Long.valueOf(12345L).equals(values.get("newPrice")) && "CENTAVOS".equals(values.get("priceUnit"))
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
        verify(f.tx).update(eq(f.order), argThat(values -> Long.valueOf(8820L).equals(values.get("totalPriceCentavos"))));
        verify(f.tx).create(eq(f.priceLog), argThat(values -> Long.valueOf(12345L).equals(values.get("previousPrice"))
                && Long.valueOf(8820L).equals(values.get("newPrice")) && "CENTAVOS".equals(values.get("priceUnit"))
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
    void serviceWithMissingUnitReturnsConflictInsteadOfThrowingNullPointerException() throws Exception {
        Fixture f = new Fixture();
        when(f.serviceSnapshot.getString("unit")).thenReturn(null);
        f.transactionHarness();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase).create(f.staff(), new ShopOrderService.CreateOrderRequest(
                        "Customer", "639171234567", "service-A", new BigDecimal("2.00"), new BigDecimal("50.00"), "")));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
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
    void paypalOrderStoresPendingPaymentAndCapturesOnlyMatchingOrder() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        when(f.orderSnapshot.getString("paymentMethodPreference")).thenReturn("ONLINE");
        when(f.orderSnapshot.getString("paymentTiming")).thenReturn("PAY_NOW");
        PayPalGateway gateway = mock(PayPalGateway.class);
        when(gateway.createOrder(new BigDecimal("123.45"), "order-A", "O-000001"))
                .thenReturn(new PayPalGateway.CreatedOrder("PP-123", "https://www.sandbox.paypal.com/checkoutnow?token=PP-123",
                        new BigDecimal("123.45"), "PHP"));
        ShopOrderService service = new ShopOrderService(f.firebase, gateway);
        ShopOrderService.PayPalOrderResult created = service.createPayPalOrder(f.staff(), "order-A");
        assertEquals("PENDING", created.order().paymentStatus());
        assertEquals("PAYPAL", created.order().paymentMethod());
        assertEquals("PP-123", created.paypalOrderId());
        assertEquals("https://www.sandbox.paypal.com/checkoutnow?token=PP-123", created.approvalUrl());
        verify(f.tx).update(eq(f.order), argThat(values -> "PENDING".equals(values.get("paymentStatus"))
                && "PAYPAL".equals(values.get("paymentMethod"))
                && "PP-123".equals(values.get("paypalOrderId"))
                && Long.valueOf(12345L).equals(values.get("paymentAmountCentavos"))
                && "order-A".equals(values.get("paymentOrderIdReference"))));
        verify(f.tx).create(eq(f.paymentRecord), argThat(values -> "shop-A".equals(values.get("shopId"))
                && "order-A".equals(values.get("orderId"))
                && "PAYPAL".equals(values.get("method"))
                && "PP-123".equals(values.get("paypalOrderId"))
                && "PENDING".equals(values.get("status"))));

        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paymentMethod")).thenReturn("PAYPAL");
        when(f.orderSnapshot.getString("paypalOrderId")).thenReturn("PP-123");
        when(f.orderSnapshot.getString("paymentLinkId")).thenReturn("PP-123");
        when(f.orderSnapshot.getString("paymentOrderIdReference")).thenReturn("order-A");
        when(f.orderSnapshot.getLong("paymentAmountCentavos")).thenReturn(12345L);
        when(f.orderSnapshot.getString("paymentRecordId")).thenReturn("payment-doc-A");
        when(gateway.captureOrder("PP-123"))
                .thenReturn(new PayPalGateway.CapturedOrder("PP-123", "COMPLETED", new BigDecimal("123.45"), "PHP", "CAP-1"));

        ShopOrderService.OrderSummary paid = service.capturePayPalOrder(f.staff(), "order-A",
                new ShopOrderService.PayPalCaptureRequest("PP-123"));
        assertEquals("PAID", paid.paymentStatus());
        assertEquals("PAYPAL", paid.paymentMethod());
        verify(f.tx).update(eq(f.order), argThat(values -> "PAID".equals(values.get("paymentStatus"))
                && "PAYPAL".equals(values.get("paymentMethod"))
                && "PAYPAL".equals(values.get("paidByUid"))));
        verify(f.tx).update(eq(f.paymentRecord), argThat(values -> "PAID".equals(values.get("status"))
                && "CAP-1".equals(values.get("externalPaymentId"))));
    }

    @Test
    void cashPreferenceRejectsPayPalOrderCreation() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        when(f.orderSnapshot.getString("paymentMethodPreference")).thenReturn("CASH");
        when(f.orderSnapshot.getString("paymentTiming")).thenReturn("PAY_NOW");
        PayPalGateway gateway = mock(PayPalGateway.class);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase, gateway).createPayPalOrder(f.staff(), "order-A"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(gateway, never()).createOrder(any(), eq("order-A"), any());
        verify(f.tx, never()).update(eq(f.order), any(Map.class));
    }

    @Test
    void paypalCaptureRejectsAmountCurrencyStatusAndOrderMismatch() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A");
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paymentMethod")).thenReturn("PAYPAL");
        when(f.orderSnapshot.getString("paypalOrderId")).thenReturn("PP-123");
        when(f.orderSnapshot.getLong("paymentAmountCentavos")).thenReturn(12345L);
        f.transactionHarness();
        PayPalGateway gateway = mock(PayPalGateway.class);

        // Wrong PayPal order ID passed
        ResponseStatusException wrongId = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase, gateway).capturePayPalOrder(
                        f.staff(), "order-A", new ShopOrderService.PayPalCaptureRequest("PP-OTHER")));
        assertEquals(HttpStatus.CONFLICT, wrongId.getStatusCode());

        // Amount mismatch
        when(gateway.captureOrder("PP-123"))
                .thenReturn(new PayPalGateway.CapturedOrder("PP-123", "COMPLETED", new BigDecimal("120.00"), "PHP", "CAP-1"));
        ResponseStatusException wrongAmount = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase, gateway).capturePayPalOrder(
                        f.staff(), "order-A", new ShopOrderService.PayPalCaptureRequest("PP-123")));
        assertEquals(HttpStatus.CONFLICT, wrongAmount.getStatusCode());

        // Currency mismatch
        when(gateway.captureOrder("PP-123"))
                .thenReturn(new PayPalGateway.CapturedOrder("PP-123", "COMPLETED", new BigDecimal("123.45"), "USD", "CAP-1"));
        ResponseStatusException wrongCurrency = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase, gateway).capturePayPalOrder(
                        f.staff(), "order-A", new ShopOrderService.PayPalCaptureRequest("PP-123")));
        assertEquals(HttpStatus.CONFLICT, wrongCurrency.getStatusCode());

        // Non-COMPLETED status
        when(gateway.captureOrder("PP-123"))
                .thenReturn(new PayPalGateway.CapturedOrder("PP-123", "PENDING", new BigDecimal("123.45"), "PHP", "CAP-1"));
        ResponseStatusException notCompleted = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase, gateway).capturePayPalOrder(
                        f.staff(), "order-A", new ShopOrderService.PayPalCaptureRequest("PP-123")));
        assertEquals(HttpStatus.CONFLICT, notCompleted.getStatusCode());
        verify(f.tx, never()).update(any(DocumentReference.class), any(Map.class));
    }

    @Test
    void paypalCaptureRejectsWrongShopAndWrongRole() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A");
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paypalOrderId")).thenReturn("PP-123");
        f.transactionHarness();
        PayPalGateway gateway = mock(PayPalGateway.class);

        FirebasePrincipal otherShopStaff = new FirebasePrincipal("s2", "s2@example.com", "Other", "u2", "STAFF", "shop-B", "ACTIVE");
        ResponseStatusException wrongShop = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase, gateway).capturePayPalOrder(
                        otherShopStaff, "order-A", new ShopOrderService.PayPalCaptureRequest("PP-123")));
        assertEquals(HttpStatus.NOT_FOUND, wrongShop.getStatusCode());

        ResponseStatusException wrongRole = assertThrows(ResponseStatusException.class,
                () -> new ShopOrderService(f.firebase, gateway).capturePayPalOrder(
                        f.owner(), "order-A", new ShopOrderService.PayPalCaptureRequest("PP-123")));
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.getStatusCode());
        verify(gateway, never()).captureOrder(any());
    }

    @Test
    void duplicatePayPalCaptureIsIdempotent() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paymentMethod")).thenReturn("PAYPAL");
        when(f.orderSnapshot.getString("paypalOrderId")).thenReturn("PP-123");
        when(f.orderSnapshot.getString("paymentLinkId")).thenReturn("PP-123");
        when(f.orderSnapshot.getString("paymentOrderIdReference")).thenReturn("order-A");
        when(f.orderSnapshot.getLong("paymentAmountCentavos")).thenReturn(12345L);
        PayPalGateway gateway = mock(PayPalGateway.class);
        when(gateway.captureOrder("PP-123"))
                .thenReturn(new PayPalGateway.CapturedOrder("PP-123", "COMPLETED", new BigDecimal("123.45"), "PHP", "CAP-1"));
        ShopOrderService service = new ShopOrderService(f.firebase, gateway);

        assertEquals("PAID", service.capturePayPalOrder(f.staff(), "order-A", null).paymentStatus());
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PAID");
        assertEquals("PAID", service.capturePayPalOrder(f.staff(), "order-A", null).paymentStatus());
        verify(gateway, times(1)).captureOrder("PP-123");
        verify(f.tx, times(1)).update(eq(f.order), argThat(values -> "PAID".equals(values.get("paymentStatus"))));
    }

    @Test
    void failedPayPalOrderCreationReleasesLockSoRetrySucceeds() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        when(f.orderSnapshot.getString("paymentMethodPreference")).thenReturn("ONLINE");
        when(f.orderSnapshot.getString("paymentTiming")).thenReturn("PAY_NOW");
        PayPalGateway gateway = mock(PayPalGateway.class);
        when(gateway.createOrder(new BigDecimal("123.45"), "order-A", "O-000001"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal failed."))
                .thenReturn(new PayPalGateway.CreatedOrder("PP-999", "https://www.sandbox.paypal.com/checkoutnow?token=PP-999",
                        new BigDecimal("123.45"), "PHP"));

        ShopOrderService service = new ShopOrderService(f.firebase, gateway);
        String reqId = "00000000-0000-4000-8000-000000000099";

        ResponseStatusException first = assertThrows(ResponseStatusException.class,
                () -> service.createPayPalOrder(f.staff(), "order-A", new ShopOrderService.PayPalOrderRequest(reqId)));
        assertEquals(HttpStatus.BAD_GATEWAY, first.getStatusCode());
        verify(f.paymentRequestDoc, times(1)).delete();

        ShopOrderService.PayPalOrderResult second = service.createPayPalOrder(
                f.staff(), "order-A", new ShopOrderService.PayPalOrderRequest(reqId));
        assertEquals("PP-999", second.paypalOrderId());
    }

    @Test
    void activeCreatingLockRejectsWith409WhileExpiredCreatingLockAllowsRetry() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        when(f.orderSnapshot.getString("paymentMethodPreference")).thenReturn("ONLINE");
        when(f.orderSnapshot.getString("paymentTiming")).thenReturn("PAY_NOW");
        String reqId = "00000000-0000-4000-8000-000000000077";

        when(f.paymentRequestSnapshot.exists()).thenReturn(true);
        when(f.paymentRequestSnapshot.getString("status")).thenReturn("CREATING");
        // Unexpired lock (+30s) -> 409 Conflict
        when(f.paymentRequestSnapshot.get("expiresAt"))
                .thenReturn(java.util.Date.from(java.time.Instant.now().plusSeconds(30)))
                .thenReturn(java.util.Date.from(java.time.Instant.now().minusSeconds(10)));

        PayPalGateway gateway = mock(PayPalGateway.class);
        when(gateway.createOrder(new BigDecimal("123.45"), "order-A", "O-000001"))
                .thenReturn(new PayPalGateway.CreatedOrder("PP-EXPIRED-OK", "https://www.sandbox.paypal.com/checkoutnow?token=PP-EXPIRED-OK",
                        new BigDecimal("123.45"), "PHP"));
        ShopOrderService service = new ShopOrderService(f.firebase, gateway);

        ResponseStatusException blocked = assertThrows(ResponseStatusException.class,
                () -> service.createPayPalOrder(f.staff(), "order-A", new ShopOrderService.PayPalOrderRequest(reqId)));
        assertEquals(HttpStatus.CONFLICT, blocked.getStatusCode());
        verify(gateway, never()).createOrder(any(), any(), any());

        // Expired lock (-10s) -> proceeds cleanly
        ShopOrderService.PayPalOrderResult retried = service.createPayPalOrder(
                f.staff(), "order-A", new ShopOrderService.PayPalOrderRequest(reqId));
        assertEquals("PP-EXPIRED-OK", retried.paypalOrderId());
    }

    @Test
    void legacyPayMongoOrderMapsAsPayMongoForBackwardCompatibility() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A");
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PAID");
        when(f.orderSnapshot.getString("paymentMethod")).thenReturn("ONLINE");
        when(f.orderSnapshot.getString("paidByUid")).thenReturn("PAYMONGO");
        when(f.orderSnapshot.getString("paymentLinkId")).thenReturn("link_legacy_123");
        when(f.shopSnapshot.getString("name")).thenReturn("Sample Shop");
        when(f.db.collection("orderStageHistory")).thenReturn(f.stageHistoryCollection);
        Query historyQuery = mock(Query.class);
        Query historyQuery2 = mock(Query.class);
        QuerySnapshot emptyQs = mock(QuerySnapshot.class);
        when(f.stageHistoryCollection.whereEqualTo("shopId", "shop-A")).thenReturn(historyQuery);
        when(historyQuery.whereEqualTo("orderId", "order-A")).thenReturn(historyQuery2);
        when(historyQuery2.get()).thenReturn(ApiFutures.immediateFuture(emptyQs));
        when(emptyQs.getDocuments()).thenReturn(List.of());
        QuerySnapshot emptyPriceQs = mock(QuerySnapshot.class);
        when(f.priceLogCollection.get()).thenReturn(ApiFutures.immediateFuture(emptyPriceQs));
        when(emptyPriceQs.getDocuments()).thenReturn(List.of());

        ShopOrderService.OrderDetail detail = new ShopOrderService(f.firebase).get(f.staff(), "order-A");
        assertEquals("PAYMONGO", detail.order().paymentMethod());
        assertEquals("PAYMONGO", detail.invoice().paymentMethod());
    }

    @Test
    void customerPaysOwnOrderWithPayPalAndCannotPayAnotherCustomersOrder() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        when(f.orderSnapshot.getString("customerUserId")).thenReturn("customer-1");
        when(f.orderSnapshot.getString("paymentMethodPreference")).thenReturn("ONLINE");
        when(f.orderSnapshot.getString("paymentTiming")).thenReturn("PAY_NOW");
        PayPalGateway gateway = mock(PayPalGateway.class);
        when(gateway.createOrder(new BigDecimal("123.45"), "order-A", "O-000001"))
                .thenReturn(new PayPalGateway.CreatedOrder("PP-CUST-10", "https://www.sandbox.paypal.com/checkoutnow?token=PP-CUST-10",
                        new BigDecimal("123.45"), "PHP"));

        ShopOrderService service = new ShopOrderService(f.firebase, gateway);
        FirebasePrincipal ownerCustomer = f.customer("customer-1");
        FirebasePrincipal otherCustomer = f.customer("customer-2");

        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> service.createCustomerPayPalOrder(otherCustomer, "order-A", null));
        assertEquals(HttpStatus.NOT_FOUND, forbidden.getStatusCode());
        verify(gateway, never()).createOrder(any(), any(), any());

        ShopOrderService.PayPalOrderResult created = service.createCustomerPayPalOrder(ownerCustomer, "order-A", null);
        assertEquals("PP-CUST-10", created.paypalOrderId());
        assertEquals("PENDING", created.order().paymentStatus());

        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paymentMethod")).thenReturn("PAYPAL");
        when(f.orderSnapshot.getString("paypalOrderId")).thenReturn("PP-CUST-10");
        when(f.orderSnapshot.getLong("paymentAmountCentavos")).thenReturn(12345L);
        when(f.orderSnapshot.getString("paymentRecordId")).thenReturn("payment-doc-A");
        when(gateway.captureOrder("PP-CUST-10"))
                .thenReturn(new PayPalGateway.CapturedOrder("PP-CUST-10", "COMPLETED", new BigDecimal("123.45"), "PHP", "CAP-CUST"));

        assertThrows(ResponseStatusException.class,
                () -> service.captureCustomerPayPalOrder(otherCustomer, "order-A",
                        new ShopOrderService.PayPalCaptureRequest("PP-CUST-10")));

        ShopOrderService.OrderSummary paid = service.captureCustomerPayPalOrder(
                ownerCustomer, "order-A", new ShopOrderService.PayPalCaptureRequest("PP-CUST-10"));
        assertEquals("PAID", paid.paymentStatus());
        assertEquals("PAYPAL", paid.paymentMethod());
    }

    @Test
    void pendingPayPalOrderIsReusedAcrossStaffCustomerAndPublicUntilExpired() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        when(f.orderSnapshot.getString("customerUserId")).thenReturn("customer-1");
        when(f.orderSnapshot.getString("paymentMethodPreference")).thenReturn("ONLINE");
        when(f.orderSnapshot.getString("paymentTiming")).thenReturn("PAY_NOW");
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paymentMethod")).thenReturn("PAYPAL");
        when(f.orderSnapshot.getString("paypalOrderId")).thenReturn("PP-SHARED-1");
        when(f.orderSnapshot.getString("paymentLinkId")).thenReturn("PP-SHARED-1");
        when(f.orderSnapshot.getString("paymentLinkUrl")).thenReturn("https://www.sandbox.paypal.com/checkoutnow?token=PP-SHARED-1");
        when(f.orderSnapshot.get("paypalOrderExpiresAt")).thenReturn(java.util.Date.from(java.time.Instant.now().plusSeconds(3600)));

        PayPalGateway gateway = mock(PayPalGateway.class);
        when(gateway.getOrderStatus("PP-SHARED-1")).thenReturn("CREATED");
        ShopOrderService service = new ShopOrderService(f.firebase, gateway);

        assertEquals("PP-SHARED-1", service.createPayPalOrder(f.staff(), "order-A").paypalOrderId());
        assertEquals("PP-SHARED-1", service.createCustomerPayPalOrder(f.customer("customer-1"), "order-A", null).paypalOrderId());
        assertEquals("PP-SHARED-1", service.createPublicPayPalOrder("order-A", null).paypalOrderId());
        verify(gateway, never()).createOrder(any(), any(), any());

        // When PayPal reports VOIDED (cancelled/expired), a new PayPal order is created
        when(gateway.getOrderStatus("PP-SHARED-1")).thenReturn("VOIDED");
        when(gateway.createOrder(new BigDecimal("123.45"), "order-A", "O-000001"))
                .thenReturn(new PayPalGateway.CreatedOrder("PP-FRESH-2", "https://www.sandbox.paypal.com/checkoutnow?token=PP-FRESH-2",
                        new BigDecimal("123.45"), "PHP"));

        ShopOrderService.PayPalOrderResult refreshed = service.createCustomerPayPalOrder(f.customer("customer-1"), "order-A", null);
        assertEquals("PP-FRESH-2", refreshed.paypalOrderId());
        verify(gateway, times(1)).createOrder(new BigDecimal("123.45"), "order-A", "O-000001");
    }

    @Test
    void publicCaptureReturnsCurrentStatusWithoutMarkingPaidWhenNotCompleted() throws Exception {
        Fixture f = new Fixture(); f.prepareExistingOrder("shop-A"); f.transactionHarness();
        when(f.orderSnapshot.getString("paymentStatus")).thenReturn("PENDING");
        when(f.orderSnapshot.getString("paymentMethod")).thenReturn("PAYPAL");
        when(f.orderSnapshot.getString("paypalOrderId")).thenReturn("PP-WALK-1");
        when(f.orderSnapshot.getLong("paymentAmountCentavos")).thenReturn(12345L);
        PayPalGateway gateway = mock(PayPalGateway.class);
        when(gateway.captureOrder("PP-WALK-1"))
                .thenReturn(new PayPalGateway.CapturedOrder("PP-WALK-1", "APPROVED", new BigDecimal("123.45"), "PHP", ""));

        ShopOrderService.OrderSummary result = new ShopOrderService(f.firebase, gateway)
                .capturePublicPayPalOrder("order-A", new ShopOrderService.PayPalCaptureRequest("PP-WALK-1"));
        assertEquals("PENDING", result.paymentStatus());
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
        final CollectionReference paymentRequestCollection = mock(CollectionReference.class);
        final DocumentReference paymentRequestDoc = mock(DocumentReference.class);
        final DocumentSnapshot paymentRequestSnapshot = mock(DocumentSnapshot.class);
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
            when(payments.document("cash-order-A")).thenReturn(paymentRecord);
            when(payments.document(startsWith("online-"))).thenReturn(paymentRecord);
            when(paymentRecord.getId()).thenReturn("payment-doc-A");
            when(payments.document("payment-doc-A")).thenReturn(paymentRecord);
            when(order.getId()).thenReturn("order-A");
            when(stageHistoryCollection.document()).thenReturn(stageHistory);
            when(order.collection("priceLog")).thenReturn(priceLogCollection);
            when(priceLogCollection.document()).thenReturn(priceLog);
            when(order.collection("paymentLinkRequests")).thenReturn(paymentRequestCollection);
            when(paymentRequestCollection.document(anyString())).thenReturn(paymentRequestDoc);
            when(paymentRequestDoc.delete()).thenReturn(ApiFutures.immediateFuture(null));
            when(shop.get()).thenReturn(ApiFutures.immediateFuture(shopSnapshot));
            when(shopSnapshot.exists()).thenReturn(true);
            when(shopSnapshot.getString("status")).thenReturn("ACTIVE");
            when(serviceSnapshot.exists()).thenReturn(true);
            when(serviceSnapshot.getId()).thenReturn("service-A");
            when(serviceSnapshot.getString("shopId")).thenReturn("shop-A");
            when(serviceSnapshot.getString("name")).thenReturn("Wash");
            when(serviceSnapshot.getString("unit")).thenReturn("kg");
            when(serviceSnapshot.getBoolean("active")).thenReturn(true);
            when(serviceSnapshot.get("pricePerUnit")).thenReturn(new BigDecimal("52.53"));
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
                if (ref == paymentRequestDoc) return ApiFutures.immediateFuture(paymentRequestSnapshot);
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
        FirebasePrincipal customer(String userId) { return new FirebasePrincipal("cust-uid-" + userId, userId + "@example.com", "Customer", userId, "CUSTOMER", null, null); }
    }
}
