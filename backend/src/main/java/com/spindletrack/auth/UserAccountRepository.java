package com.spindletrack.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class UserAccountRepository {
    private static final String USERS = "users";
    private static final String SHOPS = "shops";
    private static final String LOOKUPS = "userIdentityLookups";
    private static final String ADMIN_MARKER_COLLECTION = "systemMetadata";
    private static final String ADMIN_MARKER_ID = "platformAdmin";
    private final FirebaseAdminTokenVerifier firebase;

    public UserAccountRepository(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }
    public Optional<UserAccount> findByFirebaseUid(String uid) { return findByLookup("uid", uid); }
    public Optional<UserAccount> findByEmail(String email) { return findByLookup("email", normalizeEmail(email)); }

    public Optional<String> findShopName(String shopId) {
        if (shopId == null) return Optional.empty();
        DocumentSnapshot shop = FirestoreSupport.await(firebase.firestore().collection(SHOPS).document(shopId).get());
        return shop.exists() ? Optional.ofNullable(shop.getString("name")) : Optional.empty();
    }

    public void insertFirebaseUser(String uid, String displayName, String email, String role) {
        Firestore db = firebase.firestore();
        String normalized = normalizeEmail(email);
        FirestoreSupport.transact(db, tx -> {
            DocumentReference uidLookup = lookup("uid", uid);
            DocumentReference emailLookup = lookup("email", normalized);
            DocumentSnapshot uidExisting = FirestoreSupport.await(tx.get(uidLookup));
            DocumentSnapshot emailExisting = FirestoreSupport.await(tx.get(emailLookup));
            DocumentReference adminMarker = db.collection(ADMIN_MARKER_COLLECTION).document(ADMIN_MARKER_ID);
            DocumentSnapshot adminSnapshot = "PLATFORM_ADMIN".equals(role) ? FirestoreSupport.await(tx.get(adminMarker)) : null;
            if (uidExisting.exists() || emailExisting.exists()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
            if (adminSnapshot != null && adminSnapshot.exists()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A platform administrator is already configured.");
            DocumentReference user = db.collection(USERS).document();
            Map<String, Object> data = new HashMap<>();
            data.put("firebaseUid", uid);
            data.put("email", email == null ? "" : email.trim());
            data.put("normalizedEmail", normalized);
            data.put("displayName", displayName);
            data.put("role", role);
            data.put("active", true);
            data.put("shopId", null);
            data.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            data.put("lastLoginAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            data.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.create(user, data);
            tx.create(uidLookup, Map.of("userId", user.getId()));
            tx.create(emailLookup, Map.of("userId", user.getId()));
            if (adminSnapshot != null) tx.create(adminMarker, Map.of("userId", user.getId()));
            return null;
        });
    }

    public void bindAndPromotePlatformAdmin(String userId, String uid, String displayName, String email) {
        Firestore db = firebase.firestore();
        FirestoreSupport.transact(db, tx -> {
            DocumentReference user = db.collection(USERS).document(userId);
            DocumentSnapshot current = FirestoreSupport.await(tx.get(user));
            DocumentReference adminMarker = db.collection(ADMIN_MARKER_COLLECTION).document(ADMIN_MARKER_ID);
            DocumentSnapshot admin = FirestoreSupport.await(tx.get(adminMarker));
            String normalized = normalizeEmail(email);
            DocumentReference uidLookup = lookup("uid", uid);
            DocumentReference emailLookup = lookup("email", normalized);
            DocumentSnapshot uidExisting = FirestoreSupport.await(tx.get(uidLookup));
            DocumentSnapshot emailExisting = FirestoreSupport.await(tx.get(emailLookup));
            if (!current.exists()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
            assertAdminSlotAvailable(admin, userId);
            if ((uidExisting.exists() && !userId.equals(uidExisting.getString("userId"))) || (emailExisting.exists() && !userId.equals(emailExisting.getString("userId")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
            }
            Map<String, Object> update = identityUpdate(uid, displayName, email);
            update.put("role", "PLATFORM_ADMIN");
            update.put("shopId", com.google.cloud.firestore.FieldValue.delete());
            update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.update(user, update);
            tx.set(uidLookup, Map.of("userId", userId));
            tx.set(emailLookup, Map.of("userId", userId));
            if (!admin.exists()) tx.create(adminMarker, Map.of("userId", userId));
            return null;
        });
    }

    public void updateIdentity(String userId, String uid, String displayName, String email) {
        Firestore db = firebase.firestore();
        FirestoreSupport.transact(db, tx -> {
            DocumentReference user = db.collection(USERS).document(userId);
            DocumentSnapshot current = FirestoreSupport.await(tx.get(user));
            if (!current.exists()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before continuing.");
            String normalized = normalizeEmail(email);
            String priorEmail = normalizeEmail(firstNonBlank(current.getString("normalizedEmail"), current.getString("email")));
            DocumentReference emailLookup = lookup("email", normalized);
            DocumentReference uidLookup = lookup("uid", uid);
            DocumentSnapshot emailExisting = FirestoreSupport.await(tx.get(emailLookup));
            DocumentSnapshot uidExisting = FirestoreSupport.await(tx.get(uidLookup));
            if ((emailExisting.exists() && !userId.equals(emailExisting.getString("userId"))) || (uidExisting.exists() && !userId.equals(uidExisting.getString("userId")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
            }
            if (!priorEmail.isBlank() && !priorEmail.equals(normalized)) tx.delete(lookup("email", priorEmail));
            tx.set(emailLookup, Map.of("userId", userId));
            tx.set(uidLookup, Map.of("userId", userId));
            Map<String, Object> update = identityUpdate(uid, displayName, email);
            update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.update(user, update);
            return null;
        });
    }

    public void promotePlatformAdmin(String userId) {
        Firestore db = firebase.firestore();
        DocumentReference user = db.collection(USERS).document(userId);
        DocumentReference adminMarker = db.collection(ADMIN_MARKER_COLLECTION).document(ADMIN_MARKER_ID);
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot snapshot = FirestoreSupport.await(tx.get(user));
            DocumentSnapshot admin = FirestoreSupport.await(tx.get(adminMarker));
            if (!snapshot.exists()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before continuing.");
            assertAdminSlotAvailable(admin, userId);
            tx.update(user, Map.of("role", "PLATFORM_ADMIN", "shopId", com.google.cloud.firestore.FieldValue.delete(), "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            if (!admin.exists()) tx.create(adminMarker, Map.of("userId", userId));
            return null;
        });
    }

    public void demotePlatformAdmin(String userId) {
        Firestore db = firebase.firestore();
        DocumentReference user = db.collection(USERS).document(userId);
        DocumentReference adminMarker = db.collection(ADMIN_MARKER_COLLECTION).document(ADMIN_MARKER_ID);
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot snapshot = FirestoreSupport.await(tx.get(user));
            DocumentSnapshot admin = FirestoreSupport.await(tx.get(adminMarker));
            if (!snapshot.exists()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before continuing.");
            Map<String, Object> update = new HashMap<>();
            update.put("role", "CUSTOMER");
            update.put("shopId", null);
            update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.update(user, update);
            if (admin.exists() && userId.equals(admin.getString("userId"))) tx.delete(adminMarker);
            return null;
        });
    }

    public void setSessionClaims(String uid, String role, String shopId) { firebase.setSessionClaims(uid, role, shopId); }

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
        return new UserAccount(snapshot.getId(), snapshot.getString("firebaseUid"),
                firstNonBlank(snapshot.getString("displayName"), snapshot.getString("fullName")),
                firstNonBlank(snapshot.getString("email"), snapshot.getString("normalizedEmail")),
                snapshot.getString("role"), Boolean.TRUE.equals(snapshot.getBoolean("active")), shopId, shopStatus);
    }

    private DocumentReference lookup(String kind, String value) { return firebase.firestore().collection(LOOKUPS).document(hash(kind + ":" + value)); }
    private static Map<String, Object> identityUpdate(String uid, String displayName, String email) {
        Map<String, Object> update = new HashMap<>();
        update.put("firebaseUid", uid);
        update.put("displayName", displayName);
        update.put("email", email == null ? "" : email.trim());
        update.put("normalizedEmail", normalizeEmail(email));
        update.put("lastLoginAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return update;
    }
    private static void assertAdminSlotAvailable(DocumentSnapshot admin, String userId) {
        if (admin.exists() && !userId.equals(admin.getString("userId"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "A platform administrator is already configured.");
    }
    static String normalizeEmail(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
    private static String firstNonBlank(String first, String fallback) { return first == null || first.isBlank() ? fallback : first; }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable.", exception); }
    }
}
