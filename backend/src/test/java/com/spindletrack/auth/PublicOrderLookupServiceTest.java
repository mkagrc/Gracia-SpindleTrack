package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void matchingCodeAndMobileReturnsOnlyMaskedTrackingData() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        RegistrationRateLimitService limits = mock(RegistrationRateLimitService.class);
        Firestore db = mock(Firestore.class);
        CollectionReference orders = mock(CollectionReference.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference history = mock(CollectionReference.class);
        CollectionReference payments = mock(CollectionReference.class);
        Query orderQuery = mock(Query.class);
        Query historyQuery = mock(Query.class);
        Query paymentQuery = mock(Query.class);
        QuerySnapshot orderRows = mock(QuerySnapshot.class);
        QuerySnapshot stageRows = mock(QuerySnapshot.class);
        QuerySnapshot paymentRows = mock(QuerySnapshot.class);
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
        when(db.collection("payments")).thenReturn(payments);
        when(payments.whereEqualTo("shopId", "shop-string-1")).thenReturn(paymentQuery);
        when(paymentQuery.whereEqualTo("orderId", "order-string-1")).thenReturn(paymentQuery);
        when(paymentQuery.get()).thenReturn(ApiFutures.immediateFuture(paymentRows));
        when(paymentRows.getDocuments()).thenReturn(List.of());

        PublicOrderLookupService.WalkInOrder result = new PublicOrderLookupService(firebase, limits)
                .lookup(" o-000123 ", "+63 917 123 4567", "198.51.100.9");

        assertEquals("O-000123", result.orderCode());
        assertEquals("********4567", result.maskedMobile());
        assertEquals(com.spindletrack.LaundryStage.READY, result.currentStage());
        assertEquals("PENDING", result.paymentStatus());
        assertTrue(result.stageHistory().isEmpty());
        assertTrue(result.paymentHistory().isEmpty());
        verify(limits).recordPublicOrderLookupAttempt("198.51.100.9");
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
