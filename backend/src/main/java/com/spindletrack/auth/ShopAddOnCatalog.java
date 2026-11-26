package com.spindletrack.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopAddOnCatalog {
    private static final String COLLECTION = "addOns";
    private static final List<String> CATEGORIES = List.of("Detergent", "Fabric conditioner", "Bleach", "Other");
    private static final List<String> UNITS = List.of("piece", "sachet", "scoop", "ml");
    private final FirebaseAdminTokenVerifier firebase;

    public ShopAddOnCatalog(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }

    public record AddOnRequest(
            @NotBlank(message = "Add-on name is required.") @Size(max = 80, message = "Add-on name must be 80 characters or fewer.") String itemName,
            @NotBlank(message = "Choose an add-on category.") String category,
            @NotBlank(message = "Choose an add-on unit.") @Pattern(regexp = "piece|sachet|scoop|ml", message = "Unit must be piece, sachet, scoop, or ml.") String unit,
            @NotNull(message = "Add-on price is required.") @DecimalMin(value = "0.0", inclusive = true, message = "Add-on price cannot be negative.") @Digits(integer = 6, fraction = 2, message = "Add-on price must have at most 2 decimal places.") BigDecimal addOnPrice,
            @Min(value = 0, message = "Stock cannot be negative.") Integer stockOnHand,
            @Min(value = 1, message = "Stock deduction must be at least 1.") Integer stockDeductionPerUnit,
            boolean trackStock,
            boolean active,
            @Size(max = 300, message = "Add-on notes must be 300 characters or fewer.") String notes) {}

    public record AddOn(String id, String itemName, String category, String unit, BigDecimal addOnPrice,
            Integer stockOnHand, Integer stockDeductionPerUnit, boolean trackStock, boolean active, String notes, Instant createdAt) {}

    public List<AddOn> list(String shopId, String role) {
        String ownerShop = requireShop(shopId);
        requireActiveShop(ownerShop);
        QuerySnapshot rows = FirestoreSupport.await(firebase.firestore().collection(COLLECTION).whereEqualTo("shopId", ownerShop).get());
        List<AddOn> result = new ArrayList<>();
        for (DocumentSnapshot row : rows.getDocuments()) {
            AddOn addOn = map(row);
            if ("OWNER".equals(role) || addOn.active()) result.add("OWNER".equals(role) ? addOn : withoutStock(addOn));
        }
        result.sort(Comparator.comparing(AddOn::itemName, String.CASE_INSENSITIVE_ORDER).thenComparing(AddOn::id));
        return List.copyOf(result);
    }

    public AddOn create(String shopId, AddOnRequest request) {
        String ownerShop = requireShop(shopId);
        validate(request);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(ownerShop);
        DocumentReference ref = db.collection(COLLECTION).document();
        Instant now = Instant.now();
        Map<String, Object> values = values(ownerShop, request, true);
        FirestoreSupport.transact(db, tx -> {
            requireActiveShop(FirestoreSupport.await(tx.get(shop)));
            tx.create(ref, values);
            return null;
        });
        return new AddOn(ref.getId(), request.itemName().trim(), request.category(), request.unit(), money(request.addOnPrice()),
                request.stockOnHand(), deduction(request), request.trackStock(), request.active(), clean(request.notes()), now);
    }

    public AddOn update(String shopId, String id, AddOnRequest request) {
        String ownerShop = requireShop(shopId);
        validate(request);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(ownerShop);
        DocumentReference ref = db.collection(COLLECTION).document(requireId(id));
        FirestoreSupport.transact(db, tx -> {
            requireActiveShop(FirestoreSupport.await(tx.get(shop)));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(ref));
            requireOwned(current, ownerShop);
            tx.update(ref, values(ownerShop, request, false));
            return null;
        });
        return map(FirestoreSupport.await(ref.get()));
    }

    public AddOn setActive(String shopId, String id, boolean active) {
        String ownerShop = requireShop(shopId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(ownerShop);
        DocumentReference ref = db.collection(COLLECTION).document(requireId(id));
        FirestoreSupport.transact(db, tx -> {
            requireActiveShop(FirestoreSupport.await(tx.get(shop)));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(ref));
            requireOwned(current, ownerShop);
            tx.update(ref, Map.of("active", active, "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return null;
        });
        return map(FirestoreSupport.await(ref.get()));
    }

    static AddOn map(DocumentSnapshot snapshot) {
        Object raw = snapshot.get("addOnPriceCentavos");
        if (raw == null) raw = snapshot.get("addOnPrice");
        BigDecimal price = raw instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : BigDecimal.ZERO;
        if (snapshot.get("addOnPriceCentavos") != null) price = price.movePointLeft(2);
        return new AddOn(snapshot.getId(), first(snapshot.getString("itemName"), snapshot.getString("name")),
                first(snapshot.getString("category"), "Other"), first(snapshot.getString("unit"), "piece"), money(price),
                snapshot.getLong("stockOnHand") == null ? null : snapshot.getLong("stockOnHand").intValue(),
                snapshot.getLong("stockDeductionPerUnit") == null ? 1 : snapshot.getLong("stockDeductionPerUnit").intValue(),
                Boolean.TRUE.equals(snapshot.getBoolean("trackStock")), Boolean.TRUE.equals(snapshot.getBoolean("active")),
                snapshot.getString("notes"), instant(snapshot.get("createdAt")));
    }

    private static AddOn withoutStock(AddOn addOn) {
        return new AddOn(addOn.id(), addOn.itemName(), addOn.category(), addOn.unit(), addOn.addOnPrice(), null,
                addOn.stockDeductionPerUnit(), addOn.trackStock(), addOn.active(), addOn.notes(), addOn.createdAt());
    }
    private Map<String, Object> values(String shopId, AddOnRequest request, boolean creating) {
        Map<String, Object> values = new HashMap<>();
        values.put("shopId", shopId); values.put("itemName", request.itemName().trim()); values.put("category", request.category());
        values.put("unit", request.unit()); values.put("addOnPriceCentavos", toCentavos(request.addOnPrice()));
        values.put("stockOnHand", request.stockOnHand()); values.put("stockDeductionPerUnit", deduction(request));
        values.put("trackStock", request.trackStock()); values.put("active", request.active()); values.put("notes", clean(request.notes()));
        values.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        if (creating) values.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return values;
    }
    private void requireActiveShop(String shopId) {
        DocumentSnapshot shop = FirestoreSupport.await(firebase.firestore().collection("shops").document(shopId).get());
        requireActiveShop(shop);
    }
    private static void requireActiveShop(DocumentSnapshot shop) {
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        if (!"ACTIVE".equals(shop.getString("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Add-ons are available only while the shop is ACTIVE.");
    }
    static void validate(AddOnRequest request) {
        if (request == null || request.addOnPrice() == null || request.addOnPrice().scale() > 2 || request.addOnPrice().compareTo(BigDecimal.ZERO) < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add-on price must be non-negative with at most 2 decimal places.");
        if (request.category() == null || !CATEGORIES.contains(request.category())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown add-on category.");
        if (request.unit() == null || !UNITS.contains(request.unit())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown add-on unit.");
        if (request.trackStock() && request.stockOnHand() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock on hand is required when stock tracking is enabled.");
        if (request.stockOnHand() != null && request.stockOnHand() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock cannot be negative.");
        if (request.stockDeductionPerUnit() != null && request.stockDeductionPerUnit() < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock deduction must be at least 1.");
    }
    private static int deduction(AddOnRequest request) { return request.stockDeductionPerUnit() == null ? 1 : request.stockDeductionPerUnit(); }
    private static long toCentavos(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact(); }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String first(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static Instant instant(Object value) { java.time.LocalDateTime date = FirestoreSupport.dateTime(value); return date == null ? null : date.toInstant(java.time.ZoneOffset.UTC); }
    private static String requireShop(String shopId) { if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a shop."); return shopId; }
    private static String requireId(String id) { if (id == null || id.isBlank() || id.contains("/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid add-on ID."); return id; }
    private static void requireOwned(DocumentSnapshot row, String shopId) { if (!row.exists() || !shopId.equals(row.getString("shopId"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on was not found in this shop."); }
}
