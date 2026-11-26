package com.spindletrack.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import org.springframework.beans.factory.annotation.Value;
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
            String municipality,
            String province,
            BigDecimal distanceKm) {
        public PublicShopSummary(String shopId, String name, String address, String contactNumber,
                Double latitude, Double longitude, BigDecimal distanceKm) {
            this(shopId, name, address, contactNumber, latitude, longitude, null, null, distanceKm);
        }
    }

    /** Public shop detail: same as summary plus active services. */
    public record PublicShopDetail(
            String shopId,
            String name,
            String address,
            String contactNumber,
            Double latitude,
            Double longitude,
            String municipality,
            String province,
            List<PublicServiceEntry> services,
            List<PublicAddOnEntry> addOns) {
        public PublicShopDetail(String shopId, String name, String address, String contactNumber,
                Double latitude, Double longitude, List<PublicServiceEntry> services) {
            this(shopId, name, address, contactNumber, latitude, longitude, null, null, services, List.of());
        }
    }

    public record PublicServiceEntry(
            String name,
            String unit,
            BigDecimal referencePrice,
            String category,
            String billingType,
            BigDecimal capacityKg,
            String notes) {
        public PublicServiceEntry(String name, String unit, BigDecimal referencePrice) {
            this(name, unit, referencePrice, "Other", OrderPricing.billingType(null, unit), null, "");
        }
    }

    public record PublicAddOnEntry(String name, String category, String unit, BigDecimal price) {}

    /** Shop summary in grouped nearby search results. */
    public record NearbyShopSummary(
            String shopId,
            String name,
            String address,
            String contactNumber,
            Double latitude,
            Double longitude,
            String municipality,
            String province,
            BigDecimal distanceKm) {}

    /** One nearby municipality/city group in the grouped nearby search response. */
    public record MunicipalityGroup(String municipality, List<NearbyShopSummary> shops) {}

    /** Grouped nearby shop search result. */
    public record NearbyResult(
            String customerMunicipality,
            List<NearbyShopSummary> ownMunicipalityShops,
            boolean hasOwnMunicipalityShops,
            List<MunicipalityGroup> nearbyGroups) {}

    private static final Map<String, double[]> MUNICIPALITY_COORDS = Map.ofEntries(
            Map.entry("consolacion", new double[] {10.3772, 123.9578}),
            Map.entry("liloan", new double[] {10.4000, 123.9989}),
            Map.entry("mandaue", new double[] {10.3236, 123.9223}),
            Map.entry("lapu-lapu", new double[] {10.3103, 123.9494}),
            Map.entry("cebu city", new double[] {10.3157, 123.8854}),
            Map.entry("talisay", new double[] {10.2447, 123.8494}),
            Map.entry("compostela", new double[] {10.4567, 124.0117}),
            Map.entry("danao", new double[] {10.5208, 124.0272}),
            Map.entry("minglanilla", new double[] {10.2450, 123.7964}),
            Map.entry("cordova", new double[] {10.2519, 123.9494}),
            Map.entry("naga", new double[] {10.2094, 123.7586}),
            Map.entry("carmen", new double[] {10.5892, 124.0158}),
            Map.entry("carcar", new double[] {10.1061, 123.6403}),
            Map.entry("san fernando", new double[] {10.1636, 123.7058}),
            Map.entry("catmon", new double[] {10.7136, 124.0161}),
            Map.entry("sogod", new double[] {10.7478, 124.0003}));

    private final FirebaseAdminTokenVerifier firebase;

    @Value("${spindletrack.shops.nearby-radius-km:15.0}")
    private double defaultRadiusKm = 15.0;

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
                    shopLat, shopLng,
                    doc.getString("municipality"),
                    doc.getString("province"),
                    distance));
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
            if (svc.getString("name") == null) continue;
            ShopServiceCatalog.ShopService service = ShopServiceCatalog.map(svc);
            services.add(new PublicServiceEntry(service.name(), service.unit(), service.pricePerUnit(),
                    service.category(), service.billingType(), service.capacityKg(), service.notes()));
        }
        services.sort(Comparator.comparing(PublicServiceEntry::name, String.CASE_INSENSITIVE_ORDER));

        QuerySnapshot addOnsSnapshot = FirestoreSupport.await(db.collection("addOns")
                .whereEqualTo("shopId", shopId).whereEqualTo("active", true).get());
        List<PublicAddOnEntry> addOns = new ArrayList<>();
        for (DocumentSnapshot row : addOnsSnapshot.getDocuments()) {
            if (row.getString("itemName") == null && row.getString("name") == null) continue;
            ShopAddOnCatalog.AddOn addOn = ShopAddOnCatalog.map(row);
            addOns.add(new PublicAddOnEntry(addOn.itemName(), addOn.category(), addOn.unit(), addOn.addOnPrice()));
        }
        addOns.sort(Comparator.comparing(PublicAddOnEntry::name, String.CASE_INSENSITIVE_ORDER));

        return new PublicShopDetail(
                shop.getId(),
                shop.getString("name"),
                shop.getString("address"),
                nullToEmpty(shop.getString("contactNumber")),
                shop.getDouble("latitude"),
                shop.getDouble("longitude"),
                shop.getString("municipality"),
                shop.getString("province"),
                List.copyOf(services), List.copyOf(addOns));
    }

    /**
     * Returns ACTIVE shops grouped by municipality: own municipality first,
     * then nearby municipalities within radiusKm ordered by nearest shop distance.
     * Existing shops without coordinates are still returned in own municipality (without distance).
     */
    public NearbyResult nearbyGrouped(Double callerLat, Double callerLng, String municipality, Double radiusKm) {
        OwnerShopService.validateCoordinates(callerLat, callerLng);
        double effectiveRadius = radiusKm != null && radiusKm > 0 ? radiusKm : defaultRadiusKm;
        String normCustomerMuni = normalizeMunicipality(municipality);

        // When caller didn't supply GPS coords (manual dropdown fallback), use municipality center
        // solely to discover nearby municipalities within radius; keep displayed distanceKm null.
        Double refLat = callerLat;
        Double refLng = callerLng;
        boolean callerProvidedCoords = callerLat != null && callerLng != null;
        if (!callerProvidedCoords && normCustomerMuni != null) {
            double[] center = MUNICIPALITY_COORDS.get(normCustomerMuni.toLowerCase(Locale.ROOT));
            if (center != null) {
                refLat = center[0];
                refLng = center[1];
            }
        }

        Firestore db = firebase.firestore();
        QuerySnapshot snapshot = FirestoreSupport.await(
                db.collection("shops").whereEqualTo("status", "ACTIVE").get());

        List<NearbyShopSummary> ownShops = new ArrayList<>();
        Map<String, List<NearbyShopSummary>> nearbyByMuni = new LinkedHashMap<>();
        Map<String, BigDecimal> sortDistanceByShopId = new java.util.HashMap<>();

        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            String name = nullToEmpty(doc.getString("name"));
            String address = nullToEmpty(doc.getString("address"));
            String contact = nullToEmpty(doc.getString("contactNumber"));
            Double shopLat = doc.getDouble("latitude");
            Double shopLng = doc.getDouble("longitude");
            String rawShopMuni = doc.getString("municipality");
            String shopMuni = normalizeMunicipality(rawShopMuni);
            String shopProv = OwnerShopService.normalizeOptionalText(doc.getString("province"));

            BigDecimal sortDistance = computeDistance(refLat, refLng, shopLat, shopLng);
            if (sortDistance == null && refLat != null && shopMuni != null) {
                double[] shopCenter = MUNICIPALITY_COORDS.get(shopMuni.toLowerCase(Locale.ROOT));
                if (shopCenter != null) {
                    sortDistance = computeDistance(refLat, refLng, shopCenter[0], shopCenter[1]);
                }
            }
            BigDecimal displayDistance = callerProvidedCoords ? computeDistance(callerLat, callerLng, shopLat, shopLng) : null;

            boolean isOwnMuni = normCustomerMuni != null && shopMuni != null
                    && normCustomerMuni.equalsIgnoreCase(shopMuni);
            boolean withinRadius = sortDistance != null
                    && sortDistance.compareTo(BigDecimal.valueOf(effectiveRadius)) <= 0;

            if (!isOwnMuni && !withinRadius) {
                continue;
            }

            NearbyShopSummary summary = new NearbyShopSummary(
                    doc.getId(), name, address, contact,
                    shopLat, shopLng,
                    shopMuni != null ? shopMuni : OwnerShopService.normalizeOptionalText(rawShopMuni),
                    shopProv, displayDistance);
            if (sortDistance != null) {
                sortDistanceByShopId.put(doc.getId(), sortDistance);
            }

            if (isOwnMuni) {
                ownShops.add(summary);
            } else {
                String groupName = shopMuni != null ? shopMuni : "Other nearby";
                nearbyByMuni.computeIfAbsent(groupName, k -> new ArrayList<>()).add(summary);
            }
        }

        Comparator<NearbyShopSummary> byDistanceThenName = Comparator
                .comparing((NearbyShopSummary s) -> sortDistanceByShopId.getOrDefault(s.shopId(), BigDecimal.valueOf(Double.MAX_VALUE)))
                .thenComparing(s -> s.name().toLowerCase(Locale.ROOT));

        ownShops.sort(byDistanceThenName);

        List<MunicipalityGroup> nearbyGroups = nearbyByMuni.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> {
                    List<NearbyShopSummary> sorted = new ArrayList<>(entry.getValue());
                    sorted.sort(byDistanceThenName);
                    return new MunicipalityGroup(entry.getKey(), List.copyOf(sorted));
                })
                .sorted(Comparator.comparing(group -> group.shops().stream()
                        .map(s -> sortDistanceByShopId.get(s.shopId()))
                        .filter(d -> d != null)
                        .min(Comparator.naturalOrder())
                        .orElse(BigDecimal.valueOf(Double.MAX_VALUE))))
                .toList();

        return new NearbyResult(
                normCustomerMuni,
                List.copyOf(ownShops),
                !ownShops.isEmpty(),
                nearbyGroups);
    }

    static String normalizeMunicipality(String value) {
        String trimmed = OwnerShopService.normalizeOptionalText(value);
        if (trimmed == null) return null;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("city of ")) {
            trimmed = trimmed.substring("city of ".length()).trim();
        } else if (lower.startsWith("municipality of ")) {
            trimmed = trimmed.substring("municipality of ".length()).trim();
        }
        lower = trimmed.toLowerCase(Locale.ROOT);
        if ("cebu".equals(lower)) return "Cebu City";
        if (lower.endsWith(" city") && !"cebu city".equals(lower)) {
            trimmed = trimmed.substring(0, trimmed.length() - " city".length()).trim();
        }
        return trimmed;
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
