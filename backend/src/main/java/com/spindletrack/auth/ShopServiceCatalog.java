package com.spindletrack.auth;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final FirebaseAdminTokenVerifier firebase;

    public ShopServiceCatalog(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }

    public record ServiceRequest(
            @NotBlank(message = "Service name is required.") @Size(max = 80, message = "Service name must be 80 characters or fewer.") String name,
            @NotNull(message = "Price per unit is required.") @DecimalMin(value = "0.0", inclusive = false, message = "Price per unit must be greater than zero.") @DecimalMax(value = "100000.00", message = "Price per unit must be no more than 100000.00.") @Digits(integer = 6, fraction = 2, message = "Price per unit must have at most 2 decimal places.") BigDecimal pricePerUnit,
            @NotBlank(message = "Choose a unit: kg, piece, or load.") @Pattern(regexp = "kg|piece|load", message = "Unit must be kg, piece, or load.") String unit) {}

    public record ShopService(String id, String name, BigDecimal pricePerUnit, String unit, boolean active, Instant createdAt) {}

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
        services.sort(Comparator.comparing(ShopService::name, String.CASE_INSENSITIVE_ORDER).thenComparing(ShopService::id));
        return services;
    }

    public ShopService create(String shopId, ServiceRequest request) {
        String ownerShop = requireShop(shopId);
        validatePrice(request);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(ownerShop);
        DocumentReference service = db.collection(COLLECTION).document();
        Instant now = Instant.now();
        Map<String, Object> values = new HashMap<>();
        values.put("shopId", ownerShop);
        values.put("name", request.name().trim());
        values.put("pricePerUnit", request.pricePerUnit().doubleValue());
        values.put("unit", request.unit());
        values.put("active", true);
        values.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        values.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            requireActiveShop(shopSnapshot);
            tx.create(service, values);
            return null;
        });
        return new ShopService(service.getId(), request.name().trim(), request.pricePerUnit(), request.unit(), true, now);
    }

    public ShopService update(String shopId, String serviceId, ServiceRequest request) {
        String ownerShop = requireShop(shopId);
        validatePrice(request);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(ownerShop);
        DocumentReference service = db.collection(COLLECTION).document(requireId(serviceId));
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(service));
            requireActiveShop(shopSnapshot);
            requireOwned(current, ownerShop);
            tx.update(service, Map.of("name", request.name().trim(), "pricePerUnit", request.pricePerUnit().doubleValue(),
                    "unit", request.unit(), "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return null;
        });
        return map(FirestoreSupport.await(service.get()));
    }

    public ShopService setActive(String shopId, String serviceId, boolean active) {
        String ownerShop = requireShop(shopId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(ownerShop);
        DocumentReference service = db.collection(COLLECTION).document(requireId(serviceId));
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(service));
            requireActiveShop(shopSnapshot);
            requireOwned(current, ownerShop);
            tx.update(service, Map.of("active", active, "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return null;
        });
        return map(FirestoreSupport.await(service.get()));
    }

    private static ShopService map(DocumentSnapshot snapshot) {
        Double price = snapshot.getDouble("pricePerUnit");
        if (price == null) {
            Long whole = snapshot.getLong("pricePerUnit");
            price = whole == null ? 0.0 : whole.doubleValue();
        }
        Instant createdAt = java.util.Optional.ofNullable(FirestoreSupport.dateTime(snapshot.get("createdAt")))
                .map(value -> value.toInstant(java.time.ZoneOffset.UTC)).orElse(null);
        return new ShopService(snapshot.getId(), snapshot.getString("name"), BigDecimal.valueOf(price),
                snapshot.getString("unit"), Boolean.TRUE.equals(snapshot.getBoolean("active")), createdAt);
    }

    private static void requireOwned(DocumentSnapshot document, String shopId) {
        if (!document.exists() || !shopId.equals(document.getString("shopId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service was not found in this shop.");
        }
    }

    static void requireActiveShop(DocumentSnapshot shop) {
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        if (!"ACTIVE".equals(shop.getString("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Services are available only while the shop is ACTIVE.");
        }
    }

    private static String requireShop(String shopId) {
        if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a shop.");
        return shopId;
    }
    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.contains("/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid service ID.");
        return id;
    }
    private static void validatePrice(ServiceRequest request) {
        BigDecimal price = request == null ? null : request.pricePerUnit();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0
                || price.compareTo(new BigDecimal("100000.00")) > 0 || price.scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Price per unit must be greater than zero, no more than 100000.00, and have at most 2 decimal places.");
        }
    }
}
