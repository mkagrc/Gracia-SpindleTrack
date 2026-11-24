package com.spindletrack.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public shop directory: returns ACTIVE shops only. Never exposes owner identity,
 * staff, orders, documents, or email addresses. The caller's location is never stored.
 */
@Service
public class PublicShopDirectoryService {

    static final int PAGE_SIZE = 20;

    /** Public fields returned for every shop in a listing or detail call. */
    public record PublicShopSummary(
            String shopId,
            String name,
            String address,
            String contactNumber,
            Double latitude,
            Double longitude,
            BigDecimal distanceKm) {}

    /** Public shop detail: same as summary plus active services. */
    public record PublicShopDetail(
            String shopId,
            String name,
            String address,
            String contactNumber,
            Double latitude,
            Double longitude,
            List<PublicServiceEntry> services) {}

    public record PublicServiceEntry(
            String name,
            String unit,
            BigDecimal referencePrice) {}

    private final FirebaseAdminTokenVerifier firebase;

    public PublicShopDirectoryService(FirebaseAdminTokenVerifier firebase) {
        this.firebase = firebase;
    }

    /**
     * Returns up to PAGE_SIZE ACTIVE shops per page.
     * When lat/lng are given the results are sorted by distance (ascending).
     * Without lat/lng they are sorted alphabetically by shop name.
     * The caller's location is used only for sorting/distance; it is never stored.
     */
    public List<PublicShopSummary> search(String query, Double callerLat, Double callerLng, int page) {
        OwnerShopService.validateCoordinates(callerLat, callerLng);
        if (page < 0) page = 0;

        Firestore db = firebase.firestore();
        QuerySnapshot snapshot = FirestoreSupport.await(
                db.collection("shops").whereEqualTo("status", "ACTIVE").get());

        List<PublicShopSummary> results = new ArrayList<>();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();

        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            String name    = doc.getString("name");
            String address = doc.getString("address");
            if (name == null) name = "";
            if (address == null) address = "";

            if (!normalizedQuery.isEmpty()) {
                String lowerName    = name.toLowerCase();
                String lowerAddress = address.toLowerCase();
                if (!lowerName.contains(normalizedQuery) && !lowerAddress.contains(normalizedQuery)) {
                    continue;
                }
            }

            Double shopLat = doc.getDouble("latitude");
            Double shopLng = doc.getDouble("longitude");
            BigDecimal distance = computeDistance(callerLat, callerLng, shopLat, shopLng);
            results.add(new PublicShopSummary(
                    doc.getId(), name, address,
                    nullToEmpty(doc.getString("contactNumber")),
                    shopLat, shopLng, distance));
        }

        // Sort: by distance when caller gave coords; else alphabetically by name
        if (callerLat != null) {
            results.sort(Comparator
                    .comparing(s -> s.distanceKm() == null ? BigDecimal.valueOf(Double.MAX_VALUE) : s.distanceKm()));
        } else {
            results.sort(Comparator.comparing(s -> s.name().toLowerCase()));
        }

        int from = page * PAGE_SIZE;
        if (from >= results.size()) return List.of();
        return List.copyOf(results.subList(from, Math.min(from + PAGE_SIZE, results.size())));
    }

    /**
     * Returns the detail of one ACTIVE shop. Non-ACTIVE or unknown IDs return 404
     * so callers cannot determine whether a shop exists in another status.
     */
    public PublicShopDetail detail(String shopId) {
        if (shopId == null || shopId.isBlank() || shopId.contains("/")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        }
        Firestore db = firebase.firestore();
        DocumentSnapshot shop = FirestoreSupport.await(db.collection("shops").document(shopId).get());
        if (!shop.exists() || !"ACTIVE".equals(shop.getString("status"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        }

        // Active services only — public callers see the reference price, nothing internal
        QuerySnapshot servicesSnapshot = FirestoreSupport.await(
                db.collection("services")
                        .whereEqualTo("shopId", shopId)
                        .whereEqualTo("active", true)
                        .get());

        List<PublicServiceEntry> services = new ArrayList<>();
        for (DocumentSnapshot svc : servicesSnapshot.getDocuments()) {
            String name  = svc.getString("name");
            String unit  = svc.getString("unit");
            Double price = svc.getDouble("pricePerUnit");
            if (price == null) {
                Long whole = svc.getLong("pricePerUnit");
                price = whole == null ? 0.0 : whole.doubleValue();
            }
            if (name != null) {
                services.add(new PublicServiceEntry(
                        name, unit == null ? "kg" : unit,
                        BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP)));
            }
        }
        services.sort(Comparator.comparing(PublicServiceEntry::name, String.CASE_INSENSITIVE_ORDER));

        return new PublicShopDetail(
                shop.getId(),
                shop.getString("name"),
                shop.getString("address"),
                nullToEmpty(shop.getString("contactNumber")),
                shop.getDouble("latitude"),
                shop.getDouble("longitude"),
                List.copyOf(services));
    }

    // ── Haversine distance (km) ───────────────────────────────────────────────

    /**
     * Returns null when either party has no coordinates.
     * Never stores or logs the caller's coordinates.
     */
    static BigDecimal computeDistance(Double fromLat, Double fromLng,
                                      Double toLat,   Double toLng) {
        if (fromLat == null || fromLng == null || toLat == null || toLng == null) return null;
        double earthKm = 6371.0;
        double dLat = Math.toRadians(toLat - fromLat);
        double dLng = Math.toRadians(toLng - fromLng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(earthKm * c).setScale(3, RoundingMode.HALF_UP);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
