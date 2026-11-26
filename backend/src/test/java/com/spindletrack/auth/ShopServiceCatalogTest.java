package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ShopServiceCatalogTest {
    @Test
    void queryIsScopedByStringShopIdAndStaffOnlyReceiveActiveServices() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference services = mock(CollectionReference.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        Query query = mock(Query.class);
        QuerySnapshot rows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot active = service("service-alpha-7", "Wash and fold", true);
        QueryDocumentSnapshot inactive = service("service-beta-8", "Express", false);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("shops")).thenReturn(shops);
        when(db.collection("services")).thenReturn(services);
        when(shops.document("shop-string-A")).thenReturn(shopRef);
        when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
        when(shop.exists()).thenReturn(true);
        when(shop.getString("status")).thenReturn("ACTIVE");
        when(services.whereEqualTo("shopId", "shop-string-A")).thenReturn(query);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(rows));
        when(rows.getDocuments()).thenReturn(List.of(active, inactive));

        ShopServiceCatalog catalog = new ShopServiceCatalog(firebase);
        assertEquals(List.of("service-alpha-7"), catalog.list("shop-string-A", "STAFF").stream().map(ShopServiceCatalog.ShopService::id).toList());
        assertEquals(2, catalog.list("shop-string-A", "OWNER").size());
        verify(services, times(2)).whereEqualTo("shopId", "shop-string-A");
    }

    @Test
    void ownerCannotEditAServiceOwnedByAnotherShop() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class);
        CollectionReference services = mock(CollectionReference.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        DocumentReference serviceRef = mock(DocumentReference.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        DocumentSnapshot service = mock(DocumentSnapshot.class);
        Transaction transaction = mock(Transaction.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("shops")).thenReturn(shops);
        when(db.collection("services")).thenReturn(services);
        when(shops.document("shop-A")).thenReturn(shopRef);
        when(services.document("service-id-not-numeric")).thenReturn(serviceRef);
        when(shop.exists()).thenReturn(true);
        when(shop.getString("status")).thenReturn("ACTIVE");
        when(service.exists()).thenReturn(true);
        when(service.getString("shopId")).thenReturn("shop-B");
        when(transaction.get(shopRef)).thenReturn(ApiFutures.immediateFuture(shop));
        when(transaction.get(serviceRef)).thenReturn(ApiFutures.immediateFuture(service));
        runTransaction(db, transaction);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                new ShopServiceCatalog(firebase).update("shop-A", "service-id-not-numeric",
                        new ShopServiceCatalog.ServiceRequest("Changed", java.math.BigDecimal.ONE, "kg")));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(transaction, never()).update(any(DocumentReference.class), any());
    }

    @Test
    void serviceAllowsZeroPriceButRejectsInvalidPriceCategoryAndLoadMetadata() {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        ShopServiceCatalog catalog = new ShopServiceCatalog(firebase);
        for (java.math.BigDecimal invalid : List.of(new java.math.BigDecimal("-1.00"), new java.math.BigDecimal("1.001"),
                new java.math.BigDecimal("100000.01"))) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                    ShopServiceCatalog.validate(new ShopServiceCatalog.ServiceRequest("Wash", invalid, "kg")));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
        ShopServiceCatalog.validate(new ShopServiceCatalog.ServiceRequest("Promotion", java.math.BigDecimal.ZERO, "kg"));
        assertThrows(ResponseStatusException.class, () -> ShopServiceCatalog.validate(
                new ShopServiceCatalog.ServiceRequest("Wash", java.math.BigDecimal.ONE, "kg", null,
                        "PER_KG", null, null, "", true)));
        for (java.math.BigDecimal invalidMetadata : List.of(java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("1.001"), new java.math.BigDecimal("1000.00"))) {
            assertThrows(ResponseStatusException.class, () -> ShopServiceCatalog.validate(
                    new ShopServiceCatalog.ServiceRequest("Load", java.math.BigDecimal.ONE, "load", "Wash",
                            "PER_LOAD", invalidMetadata, null, "", true)));
        }
        assertThrows(ResponseStatusException.class, () -> ShopServiceCatalog.validate(
                new ShopServiceCatalog.ServiceRequest("Load", java.math.BigDecimal.ONE, "load", "Wash",
                        "PER_KG", null, new java.math.BigDecimal("1.001"), "", true)));
        assertThrows(ResponseStatusException.class, () -> catalog.update("shop-string-A", "service-id",
                new ShopServiceCatalog.ServiceRequest("Wash", new java.math.BigDecimal("100000.001"), "kg")));
    }

    private static QueryDocumentSnapshot service(String id, String name, boolean active) {
        QueryDocumentSnapshot snapshot = mock(QueryDocumentSnapshot.class);
        when(snapshot.getId()).thenReturn(id);
        when(snapshot.getString("name")).thenReturn(name);
        when(snapshot.getDouble("pricePerUnit")).thenReturn(28.5);
        when(snapshot.getString("unit")).thenReturn("kg");
        when(snapshot.getBoolean("active")).thenReturn(active);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static void runTransaction(Firestore db, Transaction transaction) throws Exception {
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(work.updateCallback(transaction)); }
            catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
        });
    }
}
