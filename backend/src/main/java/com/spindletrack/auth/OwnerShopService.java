package com.spindletrack.auth;

import java.util.HashMap;
import java.util.Map;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OwnerShopService {
    private final FirebaseAdminTokenVerifier firebase;
    public OwnerShopService(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }

    public record ShopProfile(String id, String name, String address, String contactNumber, String status,
            Double latitude, Double longitude, String municipality, String province) {
        public ShopProfile(String id, String name, String address, String contactNumber, String status,
                Double latitude, Double longitude) {
            this(id, name, address, contactNumber, status, latitude, longitude, null, null);
        }
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 500) String address,
            @NotBlank @Size(min = 7, max = 32) String contactNumber,
            @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90.")
            @DecimalMax(value =  "90.0", message = "Latitude must be between -90 and 90.")
            Double latitude,
            @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180.")
            @DecimalMax(value =  "180.0", message = "Longitude must be between -180 and 180.")
            Double longitude,
            @Size(max = 160) String municipality,
            @Size(max = 160) String province) {
        public UpdateRequest(String name, String address, String contactNumber, Double latitude, Double longitude) {
            this(name, address, contactNumber, latitude, longitude, null, null);
        }
    }

    public ShopProfile getMine(String shopId) {
        String id = requireShopId(shopId);
        DocumentSnapshot shop = FirestoreSupport.await(firebase.firestore().collection("shops").document(id).get());
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        requireActive(shop.getString("status"));
        return profile(shop);
    }

    public ShopProfile updateMine(String shopId, UpdateRequest request) {
        String id = requireShopId(shopId);
        validateCoordinates(request.latitude(), request.longitude());
        Firestore db = firebase.firestore();
        DocumentReference shopRef = db.collection("shops").document(id);
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shop = FirestoreSupport.await(tx.get(shopRef));
            if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
            requireActive(shop.getString("status"));
            Map<String, Object> fields = new HashMap<>();
            fields.put("name", request.name().trim());
            fields.put("address", request.address().trim());
            fields.put("contactNumber", request.contactNumber().trim());
            // null clears the coordinates; non-null sets them
            fields.put("latitude",  request.latitude());
            fields.put("longitude", request.longitude());
            fields.put("municipality", normalizeOptionalText(request.municipality()));
            fields.put("province", normalizeOptionalText(request.province()));
            fields.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.update(shopRef, fields);
            return null;
        });
        return getMine(id);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static void validateCoordinates(Double lat, Double lng) {
        // Both must be supplied together or both omitted.
        if ((lat == null) != (lng == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Latitude and longitude must both be provided or both omitted.");
        }
        if (lat != null && (lat < -90.0 || lat > 90.0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Latitude must be between -90 and 90.");
        }
        if (lng != null && (lng < -180.0 || lng > 180.0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Longitude must be between -180 and 180.");
        }
    }

    private static String requireShopId(String shopId) {
        if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This owner account is not linked to a shop.");
        return shopId;
    }
    private static void requireActive(String status) {
        if (!"ACTIVE".equals(status)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Shop profile editing is available only while the shop is ACTIVE.");
    }
    static ShopProfile profile(DocumentSnapshot shop) {
        return new ShopProfile(shop.getId(), shop.getString("name"), shop.getString("address"),
                shop.getString("contactNumber"), shop.getString("status"),
                shop.getDouble("latitude"), shop.getDouble("longitude"),
                shop.getString("municipality"), shop.getString("province"));
    }

    static String normalizeOptionalText(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
