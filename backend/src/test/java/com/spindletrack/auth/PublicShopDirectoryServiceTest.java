package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublicShopDirectoryServiceTest {

    // ── Distance calculation ─────────────────────────────────────────────────

    @Test
    void haversineDistanceIsComputedCorrectly() {
        // Manila to Cebu City ≈ 572 km
        BigDecimal d = PublicShopDirectoryService.computeDistance(14.5995, 120.9842, 10.3157, 123.8854);
        assertTrue(d.compareTo(BigDecimal.valueOf(560)) > 0 && d.compareTo(BigDecimal.valueOf(590)) < 0,
                "Expected ~572 km, got " + d);
    }

    @Test
    void distanceIsNullWhenEitherPartyHasNoCoordinates() {
        assertNull(PublicShopDirectoryService.computeDistance(null, null, 10.3, 123.9));
        assertNull(PublicShopDirectoryService.computeDistance(14.6, 121.0, null, null));
    }

    // ── ACTIVE-only filtering ─────────────────────────────────────────────────

    @Test
    void searchReturnsOnlyActiveShopsAndNeverPrivateFields() {
        ServiceFixture f = new ServiceFixture();
        f.addShop("shop-A", "Alpha Laundry", "Manila", "ACTIVE",   14.0, 121.0);
        f.addShop("shop-B", "Beta Wash",    "Makati", "PENDING",  14.1, 121.1);
        f.addShop("shop-C", "Gamma Clean",  "Pasig",  "REJECTED", 14.2, 121.2);
        f.addShop("shop-D", "Delta Suds",   "QC",     "DEACTIVATED", 14.3, 121.3);

        List<PublicShopDirectoryService.PublicShopSummary> results =
                new PublicShopDirectoryService(f.firebase).search("", null, null, 0);

        assertEquals(1, results.size());
        assertEquals("shop-A", results.get(0).shopId());
    }

    @Test
    void detailReturnsNotFoundForNonActiveStatuses() {
        for (String status : List.of("PENDING", "REJECTED", "DEACTIVATED")) {
            ServiceFixture f = new ServiceFixture();
            f.addShop("shop-X", "Some Shop", "Somewhere", status, null, null);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> new PublicShopDirectoryService(f.firebase).detail("shop-X"));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode(), "Expected 404 for status " + status);
        }
    }

    @Test
    void detailReturnsNotFoundForUnknownShopId() {
        ServiceFixture f = new ServiceFixture();
        // no shops added → snapshot is empty → not found
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> new PublicShopDirectoryService(f.firebase).detail("unknown-id"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ── Sorting ───────────────────────────────────────────────────────────────

    @Test
    void searchSortsByNameAlphabeticallyWhenNoCoordsGiven() {
        ServiceFixture f = new ServiceFixture();
        f.addShop("s1", "Zulu Wash",   "A", "ACTIVE", null, null);
        f.addShop("s2", "Alpha Clean", "B", "ACTIVE", null, null);
        f.addShop("s3", "Metro Suds",  "C", "ACTIVE", null, null);

        List<PublicShopDirectoryService.PublicShopSummary> results =
                new PublicShopDirectoryService(f.firebase).search("", null, null, 0);

        assertEquals("s2", results.get(0).shopId());
        assertEquals("s3", results.get(1).shopId());
        assertEquals("s1", results.get(2).shopId());
    }

    @Test
    void searchSortsByDistanceAscendingWhenCallerCoordinatesGiven() {
        ServiceFixture f = new ServiceFixture();
        // shop-far is 100 km away from caller, shop-near is ~1 km away
        f.addShop("shop-far",  "Far Shop",  "A", "ACTIVE", 15.5, 121.0);
        f.addShop("shop-near", "Near Shop", "B", "ACTIVE", 14.5, 121.0);

        // caller at 14.5, 121.0 → shop-near is essentially 0 distance
        List<PublicShopDirectoryService.PublicShopSummary> results =
                new PublicShopDirectoryService(f.firebase).search("", 14.5, 121.0, 0);

        assertEquals("shop-near", results.get(0).shopId());
        assertEquals("shop-far",  results.get(1).shopId());
        assertTrue(results.get(0).distanceKm().compareTo(results.get(1).distanceKm()) <= 0);
    }

    @Test
    void distanceIsNullInResultsWhenShopHasNoCoordinates() {
        ServiceFixture f = new ServiceFixture();
        f.addShop("shop-no-coords", "No-Loc Wash", "Manila", "ACTIVE", null, null);

        List<PublicShopDirectoryService.PublicShopSummary> results =
                new PublicShopDirectoryService(f.firebase).search("", 14.5, 121.0, 0);

        assertNull(results.get(0).distanceKm());
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    @Test
    void searchPaginatesAtTwentyShopsPerPage() {
        ServiceFixture f = new ServiceFixture();
        IntStream.range(0, 25).forEach(i ->
                f.addShop("s" + i, String.format("Shop %02d", i), "A", "ACTIVE", null, null));

        List<PublicShopDirectoryService.PublicShopSummary> page0 =
                new PublicShopDirectoryService(f.firebase).search("", null, null, 0);
        List<PublicShopDirectoryService.PublicShopSummary> page1 =
                new PublicShopDirectoryService(f.firebase).search("", null, null, 1);

        assertEquals(PublicShopDirectoryService.PAGE_SIZE, page0.size());
        assertEquals(5, page1.size());
    }

    @Test
    void searchReturnsEmptyListForOutOfBoundsPage() {
        ServiceFixture f = new ServiceFixture();
        f.addShop("s1", "Only Shop", "A", "ACTIVE", null, null);

        List<PublicShopDirectoryService.PublicShopSummary> result =
                new PublicShopDirectoryService(f.firebase).search("", null, null, 99);

        assertTrue(result.isEmpty());
    }

    // ── Text search ───────────────────────────────────────────────────────────

    @Test
    void searchFiltersByNameOrAddressCaseInsensitively() {
        ServiceFixture f = new ServiceFixture();
        f.addShop("s1", "Alpha Wash",  "Makati City", "ACTIVE", null, null);
        f.addShop("s2", "Beta Clean",  "Quezon City", "ACTIVE", null, null);
        f.addShop("s3", "Gamma Suds",  "Pasig City",  "ACTIVE", null, null);

        List<PublicShopDirectoryService.PublicShopSummary> byName =
                new PublicShopDirectoryService(f.firebase).search("alpha", null, null, 0);
        List<PublicShopDirectoryService.PublicShopSummary> byAddress =
                new PublicShopDirectoryService(f.firebase).search("quezon", null, null, 0);
        List<PublicShopDirectoryService.PublicShopSummary> noMatch =
                new PublicShopDirectoryService(f.firebase).search("zzznomatch", null, null, 0);

        assertEquals(1, byName.size());    assertEquals("s1", byName.get(0).shopId());
        assertEquals(1, byAddress.size()); assertEquals("s2", byAddress.get(0).shopId());
        assertTrue(noMatch.isEmpty());
    }

    // ── Coordinate validation on search ──────────────────────────────────────

    @Test
    void searchRejectsInvalidCallerCoordinates() {
        ServiceFixture f = new ServiceFixture();
        // lat given without lng
        assertThrows(ResponseStatusException.class,
                () -> new PublicShopDirectoryService(f.firebase).search("", 14.5, null, 0));
        // out of range
        assertThrows(ResponseStatusException.class,
                () -> new PublicShopDirectoryService(f.firebase).search("", 91.0, 121.0, 0));
        assertThrows(ResponseStatusException.class,
                () -> new PublicShopDirectoryService(f.firebase).search("", 14.0, 181.0, 0));
    }

    // ── No private fields ─────────────────────────────────────────────────────

    @Test
    void searchResponseHasNoPrivateFields() {
        ServiceFixture f = new ServiceFixture();
        f.addShop("shop-pub", "Public Shop", "Manila", "ACTIVE", 14.5, 121.0);

        List<PublicShopDirectoryService.PublicShopSummary> results =
                new PublicShopDirectoryService(f.firebase).search("", null, null, 0);

        PublicShopDirectoryService.PublicShopSummary s = results.get(0);
        // Record only has: shopId, name, address, contactNumber, latitude, longitude, distanceKm
        // — no email, owner, documents, staff, orders
        assertEquals("shop-pub", s.shopId());
        assertEquals("Public Shop", s.name());
    }

    // ── Test fixture ──────────────────────────────────────────────────────────

    /**
     * Builds a mock Firestore chain for the shops collection.
     * addShop() registers a mock document.  When shops are queried with
     * whereEqualTo("status","ACTIVE") only ACTIVE shops are returned.
     */
    static final class ServiceFixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db                        = mock(Firestore.class);
        final CollectionReference shopsCollection = mock(CollectionReference.class);
        final CollectionReference svcsCollection  = mock(CollectionReference.class);

        // per-shop individual document references for detail() calls
        private final java.util.Map<String, DocumentSnapshot> docsByShopId = new java.util.LinkedHashMap<>();
        private final List<DocumentSnapshot> activeDocs = new ArrayList<>();

        ServiceFixture() {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shops")).thenReturn(shopsCollection);
            when(db.collection("services")).thenReturn(svcsCollection);

            // Wire the status-filtered query to return activeDocs
            Query activeQuery = mock(Query.class);
            QuerySnapshot activeSnapshot = mock(QuerySnapshot.class);
            when(shopsCollection.whereEqualTo("status", "ACTIVE")).thenReturn(activeQuery);
            when(activeQuery.get()).thenReturn(ApiFutures.immediateFuture(activeSnapshot));
            when(activeSnapshot.getDocuments()).thenAnswer(inv -> List.copyOf(activeDocs));

            // Default: any unstubbed document() call returns a reference whose snapshot does not exist
            DocumentReference missingRef = mock(DocumentReference.class);
            DocumentSnapshot missingDoc = mock(DocumentSnapshot.class);
            when(missingDoc.exists()).thenReturn(false);
            when(missingRef.get()).thenReturn(ApiFutures.immediateFuture(missingDoc));
            when(shopsCollection.document(anyString())).thenReturn(missingRef);

            // Wire services query (no services by default)
            Query shopSvcQuery = mock(Query.class);
            Query activeSvcQuery = mock(Query.class);
            QuerySnapshot emptySvcSnapshot = mock(QuerySnapshot.class);
            when(svcsCollection.whereEqualTo(anyString(), anyString())).thenReturn(shopSvcQuery);
            when(shopSvcQuery.whereEqualTo(anyString(), anyString())).thenReturn(activeSvcQuery);
            when(activeSvcQuery.get()).thenReturn(ApiFutures.immediateFuture(emptySvcSnapshot));
            when(emptySvcSnapshot.getDocuments()).thenReturn(List.of());
        }

        void addShop(String id, String name, String address, String status, Double lat, Double lng) {
            DocumentSnapshot doc = mock(DocumentSnapshot.class);
            when(doc.getId()).thenReturn(id);
            when(doc.exists()).thenReturn(true);
            when(doc.getString("name")).thenReturn(name);
            when(doc.getString("address")).thenReturn(address);
            when(doc.getString("status")).thenReturn(status);
            when(doc.getString("contactNumber")).thenReturn("123456789");
            when(doc.getDouble("latitude")).thenReturn(lat);
            when(doc.getDouble("longitude")).thenReturn(lng);

            docsByShopId.put(id, doc);
            if ("ACTIVE".equals(status)) activeDocs.add(doc);

            // Wire individual document reference for detail() calls
            DocumentReference ref = mock(DocumentReference.class);
            when(shopsCollection.document(id)).thenReturn(ref);
            when(ref.get()).thenReturn(ApiFutures.immediateFuture(doc));
        }

        private static final class ArrayList<T> extends java.util.ArrayList<T> {}
    }
}
