package com.spindletrack.auth;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopRegistrationService {
    private final FirebaseAdminTokenVerifier firebase;
    private final UserAccountRepository users;
    private final ShopRegistrationEligibility eligibility;
    private final ShopDocumentRepository documents;
    private final ShopDocumentPolicy documentPolicy;
    private final FileStorageService fileStorage;
    private final ShopDocumentAccessPolicy documentAccess;

    public ShopRegistrationService(
            FirebaseAdminTokenVerifier firebase,
            UserAccountRepository users,
            ShopRegistrationEligibility eligibility,
            ShopDocumentRepository documents,
            ShopDocumentPolicy documentPolicy,
            FileStorageService fileStorage,
            ShopDocumentAccessPolicy documentAccess) {
        this.firebase = firebase;
        this.users = users;
        this.eligibility = eligibility;
        this.documents = documents;
        this.documentPolicy = documentPolicy;
        this.fileStorage = fileStorage;
        this.documentAccess = documentAccess;
    }

    public ShopRegistrationResponse register(
            String firebaseUid, ShopRegistrationRequest request, List<MultipartFile> documentFiles) {
        List<ValidatedShopDocument> uploads = documentPolicy.validateInitial(documentFiles);
        UserAccount account = users.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before registering your shop."));
        if (!account.active()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is inactive.");
        eligibility.requireEligible(account, false);

        List<StoredUpload> stored = storeDocuments(uploads);
        boolean committed = false;
        try {
            Firestore db = firebase.firestore();
            DocumentReference userRef = db.collection("users").document(account.id());
            DocumentReference shopRef = db.collection("shops").document();
            DocumentReference historyRef = db.collection("shopReviewHistory").document();
            List<DocumentReference> documentRefs = stored.stream().map(ignored -> db.collection("shopDocuments").document()).toList();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            String name = request.shopName().trim();
            String address = request.address().trim();
            String contactNumber = request.contactNumber().trim();
            FirestoreSupport.transact(db, tx -> {
                DocumentSnapshot current = FirestoreSupport.await(tx.get(userRef));
                if (!current.exists() || !firebaseUid.equals(current.getString("firebaseUid"))
                        || !Boolean.TRUE.equals(current.getBoolean("active"))) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before registering your shop.");
                }
                if (!"CUSTOMER".equals(current.getString("role")) || current.getString("shopId") != null) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Please use another account.");
                }
                tx.create(shopRef, shopData(shopRef.getId(), name, address, contactNumber, "PENDING", null, stored.size()));
                tx.update(userRef, Map.of("role", "OWNER", "shopId", shopRef.getId(), "updatedAt",
                        com.google.cloud.firestore.FieldValue.serverTimestamp()));
                tx.create(historyRef, historyData(shopRef.getId(), "SUBMITTED", null, account.id()));
                for (int i = 0; i < stored.size(); i++) {
                    tx.create(documentRefs.get(i), documentData(shopRef.getId(), account.id(), stored.get(i)));
                }
                return null;
            });
            committed = true;
            firebase.setSessionClaims(firebaseUid, "OWNER", shopRef.getId());
            return new ShopRegistrationResponse(shopRef.getId(), name, "PENDING",
                    "Shop registration and verification documents submitted. Waiting for approval.");
        } finally {
            if (!committed) deleteStored(stored);
        }
    }

    public ShopRegistrationResponse resubmit(
            String firebaseUid, ShopRegistrationRequest request, List<MultipartFile> documentFiles) {
        UserAccount account = requireOwner(firebaseUid);
        int existingCount = documents.countForShop(account.shopId());
        List<ValidatedShopDocument> uploads = documentPolicy.validateResubmission(account.shopStatus(), existingCount, documentFiles);
        List<StoredUpload> stored = storeDocuments(uploads);
        boolean committed = false;
        try {
            Firestore db = firebase.firestore();
            DocumentReference userRef = db.collection("users").document(account.id());
            DocumentReference shopRef = db.collection("shops").document(account.shopId());
            DocumentReference historyRef = db.collection("shopReviewHistory").document();
            List<DocumentReference> documentRefs = stored.stream().map(ignored -> db.collection("shopDocuments").document()).toList();
            String name = request.shopName().trim();
            String address = request.address().trim();
            String contactNumber = request.contactNumber().trim();
            FirestoreSupport.transact(db, tx -> {
                DocumentSnapshot user = FirestoreSupport.await(tx.get(userRef));
                DocumentSnapshot shop = FirestoreSupport.await(tx.get(shopRef));
                if (!user.exists() || !firebaseUid.equals(user.getString("firebaseUid"))
                        || !account.id().equals(user.getId()) || !"OWNER".equals(user.getString("role"))
                        || !account.shopId().equals(user.getString("shopId"))) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a shop owner can change this registration.");
                }
                if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No shop registration was found.");
                if (!"REJECTED".equals(shop.getString("status"))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The shop status changed. Refresh the registration before resubmitting.");
                }
                long count = shop.getLong("documentCount") == null ? 0 : shop.getLong("documentCount");
                if (count + stored.size() > ShopDocumentPolicy.MAX_FILES_PER_REGISTRATION) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A shop registration can have no more than five verification documents.");
                }
                if (count + stored.size() < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A resubmission must include at least one verification document.");
                Map<String, Object> update = new HashMap<>();
                update.put("name", name);
                update.put("address", address);
                update.put("contactNumber", contactNumber);
                update.put("status", "PENDING");
                update.put("rejectionReason", null);
                update.put("reviewedBy", null);
                update.put("reviewedAt", null);
                update.put("documentCount", count + stored.size());
                update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                tx.update(shopRef, update);
                tx.create(historyRef, historyData(account.shopId(), "RESUBMITTED", null, account.id()));
                for (int i = 0; i < stored.size(); i++) {
                    tx.create(documentRefs.get(i), documentData(account.shopId(), account.id(), stored.get(i)));
                }
                return null;
            });
            committed = true;
            return new ShopRegistrationResponse(account.shopId(), name, "PENDING", "Shop registration resubmitted for review.");
        } finally {
            if (!committed) deleteStored(stored);
        }
    }

    public ShopRegistrationStatusResponse findMine(String firebaseUid) {
        UserAccount account = requireOwner(firebaseUid);
        DocumentSnapshot shop = FirestoreSupport.await(firebase.firestore().collection("shops").document(account.shopId()).get());
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No shop registration was found.");
        return new ShopRegistrationStatusResponse(shop.getId(), shop.getString("name"), shop.getString("address"),
                shop.getString("contactNumber"), shop.getString("status"), shop.getString("rejectionReason"),
                FirestoreSupport.dateTime(shop.get("createdAt")), documents.findMetadataForShop(shop.getId()));
    }

    public void deleteRejectedDocument(String firebaseUid, String documentId) {
        UserAccount account = requireOwner(firebaseUid);
        documentPolicy.requireRejectedForDocumentEdit(account.shopStatus());
        ShopDocumentRecord document = documents.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document was not found."));
        if (!account.shopId().equals(document.shopId()) || !account.id().equals(document.uploadedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only edit documents you uploaded for your rejected shop.");
        }
        if (!documents.delete(account.shopId(), documentId, account.id())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document was not found.");
        }
        deleteQuietly(document.storedFilename());
    }

    public ShopDocumentDownload openDocument(String documentId, String requesterId, String role) {
        ShopDocumentRecord document = documents.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document was not found."));
        if ("OWNER".equals(role)) {
            UserAccount owner = users.findByFirebaseUid(requesterId)
                    .filter(UserAccount::active)
                    .filter(account -> "OWNER".equals(account.role()))
                    .filter(account -> account.shopId() != null && account.shopId().equals(document.shopId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "You can only open verification documents for your own shop."));
            documentAccess.requireCanOpen(role, owner.id(), document.uploadedBy());
        } else {
            documentAccess.requireCanOpen(role, requesterId, document.uploadedBy());
        }
        try {
            InputStream content = fileStorage.open(document.storedFilename());
            return new ShopDocumentDownload(content, document.originalFilename(), document.contentType(), document.sizeBytes());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The private verification document is unavailable.");
        }
    }

    private UserAccount requireOwner(String firebaseUid) {
        UserAccount account = users.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before editing your shop registration."));
        if (!account.active()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is inactive.");
        if (!"OWNER".equals(account.role()) || account.shopId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a shop owner can change this registration.");
        }
        return account;
    }

    private List<StoredUpload> storeDocuments(List<ValidatedShopDocument> uploads) {
        List<StoredUpload> stored = new ArrayList<>();
        try {
            for (ValidatedShopDocument upload : uploads) {
                try (InputStream input = upload.file().getInputStream()) {
                    String filename = fileStorage.store(input, upload.type().extension());
                    stored.add(new StoredUpload(upload, filename));
                } catch (FileStorageConfigurationException exception) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Private document storage is not configured. Set FILE_STORAGE_PATH to an absolute folder outside the repository.");
                } catch (IOException exception) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "A verification document could not be stored.");
                }
            }
            return List.copyOf(stored);
        } catch (RuntimeException exception) {
            deleteStored(stored);
            throw exception;
        }
    }

    private static Map<String, Object> shopData(String shopId, String name, String address, String contactNumber,
            String status, String reason, int documentCount) {
        Map<String, Object> data = new HashMap<>();
        data.put("shopId", shopId);
        data.put("name", name);
        data.put("address", address);
        data.put("contactNumber", contactNumber);
        data.put("status", status);
        data.put("rejectionReason", reason);
        data.put("reviewedBy", null);
        data.put("reviewedAt", null);
        data.put("documentCount", documentCount);
        data.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        data.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return data;
    }

    private static Map<String, Object> historyData(String shopId, String action, String reason, String actedBy) {
        Map<String, Object> data = new HashMap<>();
        data.put("shopId", shopId);
        data.put("action", action);
        data.put("reason", reason);
        data.put("actedBy", actedBy);
        data.put("actedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return data;
    }

    private static Map<String, Object> documentData(String shopId, String uploadedBy, StoredUpload stored) {
        Map<String, Object> data = new HashMap<>();
        data.put("shopId", shopId);
        data.put("documentType", "OTHER");
        data.put("originalFilename", stored.upload().originalFilename());
        data.put("storedFilename", stored.filename());
        data.put("contentType", stored.upload().type().contentType());
        data.put("sizeBytes", stored.upload().sizeBytes());
        data.put("uploadedBy", uploadedBy);
        data.put("uploadedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return data;
    }

    private void deleteStored(List<StoredUpload> stored) {
        stored.forEach(item -> deleteQuietly(item.filename()));
    }

    private void deleteQuietly(String filename) {
        try { fileStorage.delete(filename); } catch (IOException ignored) { }
    }

    private record StoredUpload(ValidatedShopDocument upload, String filename) {}
}
