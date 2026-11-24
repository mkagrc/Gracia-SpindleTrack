package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
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

class OwnerOperationsServiceTest {
    @Test
    void nonOwnerCannotReadReports() {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        OwnerOperationsService service = new OwnerOperationsService(firebase);
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.report(
                new FirebasePrincipal("u", "staff@example.com", "Staff", "user-u", "STAFF", "shop-A", "ACTIVE")));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void staffUnclaimedListIsRestrictedToPrincipalShopEvenIfQueryReturnsForeignRows() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class), orders = mock(CollectionReference.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        Query query = mock(Query.class);
        QuerySnapshot rows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot own = mock(QueryDocumentSnapshot.class), foreign = mock(QueryDocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("shops")).thenReturn(shops); when(shops.document("shop-A")).thenReturn(shopRef);
        when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop)); when(shop.exists()).thenReturn(true);
        when(shop.getString("status")).thenReturn("ACTIVE");
        when(db.collection("orders")).thenReturn(orders); when(orders.whereEqualTo("shopId", "shop-A")).thenReturn(query);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(rows)); when(rows.getDocuments()).thenReturn(List.of(own, foreign));
        when(own.getId()).thenReturn("order-own"); when(own.getString("shopId")).thenReturn("shop-A");
        when(own.getBoolean("unclaimed")).thenReturn(true); when(own.getString("stage")).thenReturn("READY");
        when(own.getString("orderCode")).thenReturn("O-1"); when(own.getString("customerName")).thenReturn("Own customer");
        when(own.get("quantity")).thenReturn(new BigDecimal("3.00")); when(own.getString("unit")).thenReturn("piece");
        when(foreign.getString("shopId")).thenReturn("shop-B"); when(foreign.getBoolean("unclaimed")).thenReturn(true);
        when(foreign.getString("stage")).thenReturn("READY");

        List<OwnerOperationsService.UnclaimedOrder> result = new OwnerOperationsService(firebase).unclaimed(
                new FirebasePrincipal("staff-uid", "staff@example.com", "Staff", "staff-user", "STAFF", "shop-A", "ACTIVE"));

        assertEquals(1, result.size());
        assertEquals("order-own", result.get(0).id());
        assertEquals("piece", result.get(0).unit());
        verify(orders).whereEqualTo("shopId", "shop-A");
    }

    @Test
    void reportScopesReadsToCallerShopAndProcessesOneThousandOrdersWithinFiveSeconds() {
        assertTimeout(Duration.ofSeconds(5), () -> {
            FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
            Firestore db = mock(Firestore.class);
            CollectionReference shops = mock(CollectionReference.class), orders = mock(CollectionReference.class), payments = mock(CollectionReference.class);
            DocumentReference shopRef = mock(DocumentReference.class);
            DocumentSnapshot shop = mock(DocumentSnapshot.class);
            Query orderQuery = mock(Query.class), paymentQuery = mock(Query.class);
            QuerySnapshot orderRows = mock(QuerySnapshot.class), paymentRows = mock(QuerySnapshot.class);
            when(firebase.firestore()).thenReturn(db); when(db.collection("shops")).thenReturn(shops);
            when(shops.document("shop-A")).thenReturn(shopRef); when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
            when(shop.exists()).thenReturn(true); when(shop.getString("status")).thenReturn("ACTIVE");
            when(db.collection("orders")).thenReturn(orders); when(orders.whereEqualTo("shopId", "shop-A")).thenReturn(orderQuery);
            when(orderQuery.get()).thenReturn(ApiFutures.immediateFuture(orderRows));
            when(db.collection("payments")).thenReturn(payments); when(payments.whereEqualTo("shopId", "shop-A")).thenReturn(paymentQuery);
            when(paymentQuery.get()).thenReturn(ApiFutures.immediateFuture(paymentRows));
            List<QueryDocumentSnapshot> docs = new ArrayList<>();
            Timestamp created = Timestamp.of(Date.from(Instant.now()));
            for (int i = 0; i < 1000; i++) { QueryDocumentSnapshot row = mock(QueryDocumentSnapshot.class); when(row.get("createdAt")).thenReturn(created); docs.add(row); }
            when(orderRows.getDocuments()).thenReturn(docs); when(paymentRows.getDocuments()).thenReturn(List.of());
            OwnerOperationsService.OwnerReport result = new OwnerOperationsService(firebase).report(
                    new FirebasePrincipal("u", "owner@example.com", "Owner", "user-u", "OWNER", "shop-A", "ACTIVE"));
            assertEquals(1000, result.thisMonth().orderCount());
            verify(orders).whereEqualTo("shopId", "shop-A"); verify(payments).whereEqualTo("shopId", "shop-A");
        });
    }

    @Test
    void reportReconcilesCentavoAndLegacyPaidRecordsForOnlyTheOwnerShop() {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class), orders = mock(CollectionReference.class), payments = mock(CollectionReference.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        Query orderQuery = mock(Query.class), paymentQuery = mock(Query.class);
        QuerySnapshot orderRows = mock(QuerySnapshot.class), paymentRows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot online = mock(QueryDocumentSnapshot.class), cashLegacy = mock(QueryDocumentSnapshot.class);
        Timestamp paidAt = Timestamp.of(Date.from(Instant.now()));
        when(firebase.firestore()).thenReturn(db); when(db.collection("shops")).thenReturn(shops);
        when(shops.document("shop-A")).thenReturn(shopRef); when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
        when(shop.exists()).thenReturn(true); when(shop.getString("status")).thenReturn("ACTIVE");
        when(db.collection("orders")).thenReturn(orders); when(orders.whereEqualTo("shopId", "shop-A")).thenReturn(orderQuery);
        when(orderQuery.get()).thenReturn(ApiFutures.immediateFuture(orderRows)); when(orderRows.getDocuments()).thenReturn(List.of());
        when(db.collection("payments")).thenReturn(payments); when(payments.whereEqualTo("shopId", "shop-A")).thenReturn(paymentQuery);
        when(paymentQuery.get()).thenReturn(ApiFutures.immediateFuture(paymentRows));
        when(paymentRows.getDocuments()).thenReturn(List.of(online, cashLegacy));
        when(online.getString("status")).thenReturn("PAID"); when(online.get("paidAt")).thenReturn(paidAt);
        when(online.get("amountCentavos")).thenReturn(1_025L);
        when(cashLegacy.getString("status")).thenReturn("PAID"); when(cashLegacy.get("paidAt")).thenReturn(paidAt);
        when(cashLegacy.get("amount")).thenReturn(new BigDecimal("14.75"));

        OwnerOperationsService.OwnerReport result = new OwnerOperationsService(firebase).report(
                new FirebasePrincipal("u", "owner@example.com", "Owner", "user-u", "OWNER", "shop-A", "ACTIVE"));

        assertEquals(new BigDecimal("25.00"), result.today().income());
        assertEquals(new BigDecimal("25.00"), result.thisMonth().income());
        verify(orders).whereEqualTo("shopId", "shop-A");
        verify(payments).whereEqualTo("shopId", "shop-A");
    }
}
