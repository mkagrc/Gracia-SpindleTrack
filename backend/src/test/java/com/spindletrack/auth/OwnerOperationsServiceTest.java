package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
