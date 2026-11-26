package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

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

class NearbyShopDirectoryServiceTest {

    @Test
    void ownMunicipalityShopsAppearFirstOrderedByDistance() {
        Fixture f = new Fixture();
        // Customer at Consolacion center (10.3772, 123.9578)
        f.addShop("shop-far", "Consolacion Far", "Nangka", "ACTIVE", 10.3900, 123.9700, "Consolacion", "Cebu");
        f.addShop("shop-near", "Consolacion Near", "Poblacion", "ACTIVE", 10.3775, 123.9580, "Consolacion", "Cebu");
        f.addShop("shop-liloan", "Liloan Wash", "Yati", "ACTIVE", 10.4000, 123.9989, "Liloan", "Cebu");

        PublicShopDirectoryService.NearbyResult result = new PublicShopDirectoryService(f.firebase)
                .nearbyGrouped(10.3772, 123.9578, "Consolacion", 15.0);

        assertEquals("Consolacion", result.customerMunicipality());
        assertTrue(result.hasOwnMunicipalityShops());
        assertEquals(2, result.ownMunicipalityShops().size());
        assertEquals("shop-near", result.ownMunicipalityShops().get(0).shopId());
        assertEquals("shop-far", result.ownMunicipalityShops().get(1).shopId());
        assertEquals(1, result.nearbyGroups().size());
        assertEquals("Liloan", result.nearbyGroups().get(0).municipality());
        assertEquals("shop-liloan", result.nearbyGroups().get(0).shops().get(0).shopId());
    }

    @Test
    void nearbyGroupsAreOrderedByNearestShopAndSkipEmptyGroupsAndNonActiveShops() {
        Fixture f = new Fixture();
        // Customer at Consolacion (10.3772, 123.9578)
        f.addShop("shop-liloan", "Liloan Wash", "Yati", "ACTIVE", 10.4000, 123.9989, "Liloan", "Cebu"); // ~5.2 km
        f.addShop("shop-mandaue", "Mandaue Clean", "Centro", "ACTIVE", 10.3236, 123.9223, "Mandaue", "Cebu"); // ~7.1 km
        f.addShop("shop-pending", "Pending Wash", "Liloan", "PENDING", 10.3950, 123.9800, "Liloan", "Cebu");
        f.addShop("shop-far-danao", "Danao Wash", "Poblacion", "ACTIVE", 10.5208, 124.0272, "Danao", "Cebu"); // ~17.7 km > 15 km

        PublicShopDirectoryService.NearbyResult result = new PublicShopDirectoryService(f.firebase)
                .nearbyGrouped(10.3772, 123.9578, "Consolacion", 15.0);

        assertFalse(result.hasOwnMunicipalityShops());
        assertTrue(result.ownMunicipalityShops().isEmpty());
        assertEquals(2, result.nearbyGroups().size());
        assertEquals("Liloan", result.nearbyGroups().get(0).municipality());
        assertEquals("Mandaue", result.nearbyGroups().get(1).municipality());
    }

    @Test
    void shopWithoutCoordsIncludedIfMunicipalityMatches() {
        Fixture f = new Fixture();
        f.addShop("shop-nocoord", "Legacy Wash", "Consolacion", "ACTIVE", null, null, "Consolacion", "Cebu");
        f.addShop("shop-other-nocoord", "Legacy Liloan", "Liloan", "ACTIVE", null, null, "Liloan", "Cebu");

        PublicShopDirectoryService.NearbyResult result = new PublicShopDirectoryService(f.firebase)
                .nearbyGrouped(10.3772, 123.9578, "Consolacion", 15.0);

        assertTrue(result.hasOwnMunicipalityShops());
        assertEquals(1, result.ownMunicipalityShops().size());
        assertEquals("shop-nocoord", result.ownMunicipalityShops().get(0).shopId());
        assertNull(result.ownMunicipalityShops().get(0).distanceKm());
    }

    @Test
    void manualMunicipalityFallbackWorksWithoutCallerCoordinates() {
        Fixture f = new Fixture();
        f.addShop("shop-c1", "Consolacion Shop", "Poblacion", "ACTIVE", 10.3772, 123.9578, "Consolacion", "Cebu");
        f.addShop("shop-l1", "Liloan Shop", "Yati", "ACTIVE", 10.4000, 123.9989, "Liloan", "Cebu");
        f.addShop("shop-d1", "Danao Shop", "Centro", "ACTIVE", 10.5208, 124.0272, "Danao", "Cebu"); // > 15 km from Consolacion

        PublicShopDirectoryService.NearbyResult result = new PublicShopDirectoryService(f.firebase)
                .nearbyGrouped(null, null, "Consolacion", 15.0);

        assertEquals("Consolacion", result.customerMunicipality());
        assertEquals(1, result.ownMunicipalityShops().size());
        assertNull(result.ownMunicipalityShops().get(0).distanceKm());
        assertEquals(1, result.nearbyGroups().size());
        assertEquals("Liloan", result.nearbyGroups().get(0).municipality());
        assertNull(result.nearbyGroups().get(0).shops().get(0).distanceKm());
    }

    @Test
    void invalidCallerCoordinatesReturn400() {
        Fixture f = new Fixture();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> new PublicShopDirectoryService(f.firebase).nearbyGrouped(95.0, 123.9, "Consolacion", 15.0));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final List<DocumentSnapshot> activeDocs = new ArrayList<>();

        Fixture() {
            Firestore db = mock(Firestore.class);
            CollectionReference shopsCollection = mock(CollectionReference.class);
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shops")).thenReturn(shopsCollection);

            Query activeQuery = mock(Query.class);
            QuerySnapshot activeSnapshot = mock(QuerySnapshot.class);
            when(shopsCollection.whereEqualTo("status", "ACTIVE")).thenReturn(activeQuery);
            when(activeQuery.get()).thenReturn(ApiFutures.immediateFuture(activeSnapshot));
            when(activeSnapshot.getDocuments()).thenAnswer(inv -> List.copyOf(activeDocs));

            DocumentReference missingRef = mock(DocumentReference.class);
            DocumentSnapshot missingDoc = mock(DocumentSnapshot.class);
            when(missingDoc.exists()).thenReturn(false);
            when(missingRef.get()).thenReturn(ApiFutures.immediateFuture(missingDoc));
            when(shopsCollection.document(anyString())).thenReturn(missingRef);
        }

        void addShop(String id, String name, String address, String status,
                     Double lat, Double lng, String municipality, String province) {
            DocumentSnapshot doc = mock(DocumentSnapshot.class);
            when(doc.getId()).thenReturn(id);
            when(doc.exists()).thenReturn(true);
            when(doc.getString("name")).thenReturn(name);
            when(doc.getString("address")).thenReturn(address);
            when(doc.getString("status")).thenReturn(status);
            when(doc.getString("contactNumber")).thenReturn("123456789");
            when(doc.getDouble("latitude")).thenReturn(lat);
            when(doc.getDouble("longitude")).thenReturn(lng);
            when(doc.getString("municipality")).thenReturn(municipality);
            when(doc.getString("province")).thenReturn(province);
            if ("ACTIVE".equals(status)) {
                activeDocs.add(doc);
            }
        }
    }
}
