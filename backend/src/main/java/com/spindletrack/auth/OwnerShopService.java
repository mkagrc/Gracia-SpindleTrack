package com.spindletrack.auth;

import java.util.Map;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OwnerShopService {
    private final FirebaseAdminTokenVerifier firebase;
    public OwnerShopService(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }

    public record ShopProfile(String id, String name, String address, String contactNumber, String status) {}
    public record UpdateRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=160) String name,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String address,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min=7,max=32) String contactNumber) {}

    public ShopProfile getMine(String shopId) {
        String id = requireShopId(shopId);
        DocumentSnapshot shop = FirestoreSupport.await(firebase.firestore().collection("shops").document(id).get());
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        requireActive(shop.getString("status"));
        return profile(shop);
    }

    public ShopProfile updateMine(String shopId, UpdateRequest request) {
        String id = requireShopId(shopId);
        Firestore db = firebase.firestore();
        DocumentReference shopRef = db.collection("shops").document(id);
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shop = FirestoreSupport.await(tx.get(shopRef));
            if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
            requireActive(shop.getString("status"));
            tx.update(shopRef, Map.of("name", request.name().trim(), "address", request.address().trim(),
                    "contactNumber", request.contactNumber().trim(), "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return null;
        });
        return getMine(id);
    }

    private static String requireShopId(String shopId) {
        if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This owner account is not linked to a shop.");
        return shopId;
    }
    private static void requireActive(String status) {
        if (!"ACTIVE".equals(status)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Shop profile editing is available only while the shop is ACTIVE.");
    }
    private static ShopProfile profile(DocumentSnapshot shop) {
        return new ShopProfile(shop.getId(), shop.getString("name"), shop.getString("address"),
                shop.getString("contactNumber"), shop.getString("status"));
    }
}
