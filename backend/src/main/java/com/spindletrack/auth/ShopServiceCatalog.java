package com.spindletrack.auth;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopServiceCatalog {
    private static final String COLLECTION = "services";
    private static final List<String> CATEGORIES = List.of("Wash", "Dry", "Fold", "Wash-Dry-Fold", "Full service", "Heavy items", "Self-service", "Other");
    private static final List<String> BILLING_TYPES = List.of("PER_KG", "PER_LOAD", "PER_PIECE", "FLAT");
    private final FirebaseAdminTokenVerifier firebase;
    public ShopServiceCatalog(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }

    /** pricePerUnit/unit are retained as JSON aliases and Java accessors for old clients and documents. */
    public record ServiceRequest(
            @NotBlank(message = "Service name is required.") @Size(max = 80, message = "Service name must be 80 characters or fewer.") String name,
            @JsonAlias({"price", "pricePerUnit"}) @NotNull(message = "Price is required.") @DecimalMin(value = "0.0", inclusive = true, message = "Price cannot be negative.") @DecimalMax(value = "100000.00", message = "Price must be no more than 100000.00.") @Digits(integer = 6, fraction = 2, message = "Price must have at most 2 decimal places.") BigDecimal pricePerUnit,
            @Pattern(regexp = "kg|piece|load|flat|PER_KG|PER_LOAD|PER_PIECE|FLAT", message = "Choose a valid legacy unit.") String unit,
            @NotBlank(message = "Choose a service category.") String category, String billingType, BigDecimal capacityKg, BigDecimal minimumKg,
            @Size(max = 300, message = "Service notes must be 300 characters or fewer.") String notes,
            Boolean active) {
        public ServiceRequest(String name, BigDecimal price, String unit) { this(name, price, unit, "Other", null, null, null, null, null); }
        public BigDecimal price() { return pricePerUnit; }
        public String billingTypeValue() { return normalizeBillingType(billingType == null ? unit : billingType); }
    }

    public record ShopService(String id, String name, BigDecimal pricePerUnit, String unit, boolean active, Instant createdAt,
            String category, String billingType, BigDecimal capacityKg, BigDecimal minimumKg, String notes) {
        public ShopService(String id, String name, BigDecimal price, String unit, boolean active, Instant createdAt) {
            this(id, name, price, unit, active, createdAt, "Other", normalizeBillingType(unit), null, null, "");
        }
        public BigDecimal price() { return pricePerUnit; }
    }

    public List<ShopService> list(String shopId, String role) {
        String ownerShop = requireShop(shopId);
        Firestore db = firebase.firestore();
        requireActiveShop(FirestoreSupport.await(db.collection("shops").document(ownerShop).get()));
        QuerySnapshot result = FirestoreSupport.await(db.collection(COLLECTION).whereEqualTo("shopId", ownerShop).get());
        List<ShopService> services = new ArrayList<>();
        for (DocumentSnapshot snapshot : result.getDocuments()) {
            ShopService service = map(snapshot);
            if ("OWNER".equals(role) || service.active()) services.add(service);
        }
        services.sort(Comparator.comparing(ShopService::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(ShopService::id));
        return services;
    }

    public ShopService create(String shopId, ServiceRequest request) {
        String ownerShop = requireShop(shopId); validate(request);
        Firestore db = firebase.firestore(); DocumentReference shop = db.collection("shops").document(ownerShop); DocumentReference ref = db.collection(COLLECTION).document();
        Instant now = Instant.now(); Map<String,Object> values = values(ownerShop, request, true);
        FirestoreSupport.transact(db, tx -> { requireActiveShop(FirestoreSupport.await(tx.get(shop))); tx.create(ref, values); return null; });
        String billing = request.billingTypeValue();
        return new ShopService(ref.getId(), request.name().trim(), money(request.pricePerUnit()), derivedUnit(billing), true, now,
                category(request.category()), billing, request.capacityKg(), request.minimumKg(), clean(request.notes()));
    }

    public ShopService update(String shopId, String serviceId, ServiceRequest request) {
        String ownerShop = requireShop(shopId); validate(request); Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(ownerShop); DocumentReference ref = db.collection(COLLECTION).document(requireId(serviceId));
        FirestoreSupport.transact(db, tx -> { requireActiveShop(FirestoreSupport.await(tx.get(shop))); DocumentSnapshot current = FirestoreSupport.await(tx.get(ref)); requireOwned(current, ownerShop); tx.update(ref, values(ownerShop, request, false)); return null; });
        return map(FirestoreSupport.await(ref.get()));
    }

    public ShopService setActive(String shopId, String serviceId, boolean active) {
        String ownerShop = requireShop(shopId); Firestore db = firebase.firestore(); DocumentReference shop = db.collection("shops").document(ownerShop); DocumentReference ref = db.collection(COLLECTION).document(requireId(serviceId));
        FirestoreSupport.transact(db, tx -> { requireActiveShop(FirestoreSupport.await(tx.get(shop))); DocumentSnapshot current = FirestoreSupport.await(tx.get(ref)); requireOwned(current, ownerShop); tx.update(ref, Map.of("active", active, "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp())); return null; });
        return map(FirestoreSupport.await(ref.get()));
    }

    static ShopService map(DocumentSnapshot snapshot) {
        Object raw = snapshot.get("pricePerUnit"); BigDecimal price = raw instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
        String oldUnit = snapshot.getString("unit"); String billing = normalizeBillingType(first(snapshot.getString("billingType"), oldUnit));
        String unit = derivedUnit(billing); if (snapshot.getString("billingType") == null && oldUnit != null) unit = oldUnit;
        Instant createdAt = java.util.Optional.ofNullable(FirestoreSupport.dateTime(snapshot.get("createdAt"))).map(v -> v.toInstant(java.time.ZoneOffset.UTC)).orElse(null);
        return new ShopService(snapshot.getId(), first(snapshot.getString("name"), ""), money(price), unit, Boolean.TRUE.equals(snapshot.getBoolean("active")), createdAt,
                category(snapshot.getString("category")), billing, decimal(snapshot.get("capacityKg")), decimal(snapshot.get("minimumKg")), first(snapshot.getString("notes"), ""));
    }

    private static Map<String,Object> values(String shopId, ServiceRequest request, boolean create) {
        String billing = request.billingTypeValue(); Map<String,Object> v = new HashMap<>(); v.put("shopId", shopId); v.put("name", request.name().trim()); v.put("pricePerUnit", request.pricePerUnit().doubleValue()); v.put("unit", derivedUnit(billing)); v.put("category", category(request.category())); v.put("billingType", billing); v.put("capacityKg", request.capacityKg()); v.put("minimumKg", request.minimumKg()); v.put("notes", clean(request.notes())); v.put("active", request.active() == null || request.active()); v.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()); if (create) v.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp()); return v;
    }
    static void validate(ServiceRequest request) {
        if (request == null || request.pricePerUnit() == null || request.pricePerUnit().compareTo(BigDecimal.ZERO) < 0 || request.pricePerUnit().compareTo(new BigDecimal("100000.00")) > 0 || request.pricePerUnit().scale() > 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price must be from 0.00 to 100000.00 and have at most 2 decimal places.");
        String billing = request.billingTypeValue(); if (!BILLING_TYPES.contains(billing)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid billing type."); if (request.category() == null || !CATEGORIES.contains(request.category())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid service category.");
        if (request.capacityKg() != null && (request.capacityKg().compareTo(BigDecimal.ZERO) <= 0 || request.capacityKg().compareTo(new BigDecimal("999.99")) > 0 || request.capacityKg().scale() > 2)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Capacity must be greater than zero, at most 999.99, and have at most 2 decimal places.");
        if (request.minimumKg() != null && (request.minimumKg().compareTo(BigDecimal.ZERO) <= 0 || request.minimumKg().compareTo(new BigDecimal("999.99")) > 0 || request.minimumKg().scale() > 2)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum weight must be greater than zero, at most 999.99, and have at most 2 decimal places.");
        if (request.capacityKg() != null && !"PER_LOAD".equals(billing)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Capacity is only available for per-load services.");
        if (request.minimumKg() != null && !"PER_KG".equals(billing)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum weight is only available for per-kilogram services.");
    }
    private static String normalizeBillingType(String value) { if (value == null) return "PER_KG"; return switch (value.toLowerCase()) { case "kg" -> "PER_KG"; case "load" -> "PER_LOAD"; case "piece" -> "PER_PIECE"; default -> value.toUpperCase(); }; }
    private static String derivedUnit(String billing) { return switch (billing) { case "PER_LOAD" -> "load"; case "PER_PIECE" -> "piece"; case "FLAT" -> "flat"; default -> "kg"; }; }
    private static String category(String value) { return value == null || value.isBlank() ? "Other" : value; }
    private static String first(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static BigDecimal decimal(Object value) { return value instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null; }
    private static BigDecimal money(BigDecimal v) { return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, java.math.RoundingMode.HALF_UP); }
    private static void requireOwned(DocumentSnapshot d, String shopId) { if (!d.exists() || !shopId.equals(d.getString("shopId"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service was not found in this shop."); }
    static void requireActiveShop(DocumentSnapshot shop) { if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found."); if (!"ACTIVE".equals(shop.getString("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Services are available only while the shop is ACTIVE."); }
    private static String requireShop(String shopId) { if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a shop."); return shopId; }
    private static String requireId(String id) { if (id == null || id.isBlank() || id.contains("/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid service ID."); return id; }
}
