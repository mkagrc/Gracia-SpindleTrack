package com.spindletrack.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class UserAccountRepository {
    private static final String USERS = "users";
    private static final String SHOPS = "shops";
    private static final String LOOKUPS = "userIdentityLookups";
    private final FirebaseAdminTokenVerifier firebase;

    public UserAccountRepository(FirebaseAdminTokenVerifier firebase) {
        this.firebase = firebase;
    }

    public Optional<UserAccount> findByFirebaseUid(String uid) {
        return findByLookup("uid", uid);
    }

    public Optional<UserAccount> findByEmail(String email) {
        return findByLookup("email", normalizeEmail(email));
    }

    public Optional<String> findShopName(String shopId) {
        if (shopId == null) return Optional.empty();
        DocumentSnapshot shop = FirestoreSupport.await(firebase.firestore().collection(SHOPS).document(shopId).get());
        return shop.exists() ? Optional.ofNullable(shop.getString("name")) : Optional.empty();
    }

    public void insertFirebaseUser(String uid, String fullName, String email, String role) {
        Firestore db = firebase.firestore();
        String normalized = normalizeEmail(email);
        FirestoreSupport.transact(db, tx -> {
            DocumentReference uidLookup = lookup("uid", uid);
            DocumentReference emailLookup = lookup("email", normalized);
            DocumentSnapshot uidExisting = FirestoreSupport.await(tx.get(uidLookup));
            DocumentSnapshot emailExisting = FirestoreSupport.await(tx.get(emailLookup));
            if (uidExisting.exists() || emailExisting.exists()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
            }
            DocumentReference user = db.collection(USERS).document();
            Map<String, Object> data = new HashMap<>();
            data.put("firebaseUid", uid);
            data.put("fullName", fullName);
            data.put("email", normalized);
            data.put("role", role);
            data.put("active", true);
            data.put("shopId", null);
            data.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            data.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.create(user, data);
            tx.create(uidLookup, Map.of("userId", user.getId()));
            tx.create(emailLookup, Map.of("userId", user.getId()));
            return null;
        });
    }

    public void bindAndPromotePlatformAdmin(String userId, String uid, String fullName, String email) {
        Firestore db = firebase.firestore();
        FirestoreSupport.transact(db, tx -> {
            DocumentReference user = db.collection(USERS).document(userId);
            DocumentSnapshot current = FirestoreSupport.await(tx.get(user));
            if (!current.exists()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
            String normalized = normalizeEmail(email);
            DocumentReference uidLookup = lookup("uid", uid);
            DocumentReference emailLookup = lookup("email", normalized);
            DocumentSnapshot uidExisting = FirestoreSupport.await(tx.get(uidLookup));
            DocumentSnapshot emailExisting = FirestoreSupport.await(tx.get(emailLookup));
            if ((uidExisting.exists() && !userId.equals(uidExisting.getString("userId")))
                    || (emailExisting.exists() && !userId.equals(emailExisting.getString("userId")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
            }
            tx.update(user, Map.of("firebaseUid", uid, "fullName", fullName, "email", normalized,
                    "role", "PLATFORM_ADMIN", "shopId", com.google.cloud.firestore.FieldValue.delete(),
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            tx.set(uidLookup, Map.of("userId", userId));
            tx.set(emailLookup, Map.of("userId", userId));
            return null;
        });
    }

    public void updateIdentity(String userId, String uid, String fullName, String email) {
        Firestore db = firebase.firestore();
        FirestoreSupport.transact(db, tx -> {
            DocumentReference user = db.collection(USERS).document(userId);
            DocumentSnapshot current = FirestoreSupport.await(tx.get(user));
            if (!current.exists()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before continuing.");
            String normalized = normalizeEmail(email);
            String priorEmail = normalizeEmail(current.getString("email"));
            DocumentReference emailLookup = lookup("email", normalized);
            DocumentReference uidLookup = lookup("uid", uid);
            DocumentSnapshot emailExisting = FirestoreSupport.await(tx.get(emailLookup));
            DocumentSnapshot uidExisting = FirestoreSupport.await(tx.get(uidLookup));
            if ((emailExisting.exists() && !userId.equals(emailExisting.getString("userId")))
                    || (uidExisting.exists() && !userId.equals(uidExisting.getString("userId")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
            }
            if (!priorEmail.isBlank() && !priorEmail.equals(normalized)) tx.delete(lookup("email", priorEmail));
            tx.set(emailLookup, Map.of("userId", userId));
            tx.set(uidLookup, Map.of("userId", userId));
            tx.update(user, Map.of("firebaseUid", uid, "fullName", fullName, "email", normalized,
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return null;
        });
    }

    public void promotePlatformAdmin(String userId) {
        DocumentReference user = firebase.firestore().collection(USERS).document(userId);
        FirestoreSupport.transact(firebase.firestore(), tx -> {
            DocumentSnapshot snapshot = FirestoreSupport.await(tx.get(user));
            if (!snapshot.exists()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before continuing.");
            tx.update(user, Map.of("role", "PLATFORM_ADMIN", "shopId", nullValue(),
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return null;
        });
    }

    private Optional<UserAccount> findByLookup(String kind, String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        Firestore db = firebase.firestore();
        DocumentSnapshot pointer = FirestoreSupport.await(lookup(kind, value).get());
        if (!pointer.exists()) return Optional.empty();
        String userId = pointer.getString("userId");
        if (userId == null) return Optional.empty();
        DocumentSnapshot user = FirestoreSupport.await(db.collection(USERS).document(userId).get());
        return user.exists() ? Optional.of(mapAccount(db, user)) : Optional.empty();
    }

    private UserAccount mapAccount(Firestore db, DocumentSnapshot snapshot) {
        String shopId = snapshot.getString("shopId");
        String shopStatus = null;
        if (shopId != null) {
            DocumentSnapshot shop = FirestoreSupport.await(db.collection(SHOPS).document(shopId).get());
            if (shop.exists()) shopStatus = shop.getString("status");
        }
        return new UserAccount(snapshot.getId(), snapshot.getString("firebaseUid"), snapshot.getString("fullName"),
                snapshot.getString("email"), snapshot.getString("role"), Boolean.TRUE.equals(snapshot.getBoolean("active")),
                shopId, shopStatus);
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private DocumentReference lookup(String kind, String value) {
        return firebase.firestore().collection(LOOKUPS).document(hash(kind + ":" + value));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static Object nullValue() {
        return com.google.cloud.firestore.FieldValue.delete();
    }
}
