package com.spindletrack.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ShopDocumentRepository {
    private static final String COLLECTION = "shopDocuments";
    private final FirebaseAdminTokenVerifier firebase;

    public ShopDocumentRepository(FirebaseAdminTokenVerifier firebase) {
        this.firebase = firebase;
    }

    public int countForShop(String shopId) {
        DocumentSnapshot shop = FirestoreSupport.await(firebase.firestore().collection("shops").document(shopId).get());
        return shop.exists() && shop.getLong("documentCount") != null ? Math.toIntExact(shop.getLong("documentCount")) : 0;
    }

    public List<ShopDocumentMetadata> findMetadataForShop(String shopId) {
        List<QueryDocumentSnapshot> docs = FirestoreSupport.await(firebase.firestore().collection(COLLECTION)
                .whereEqualTo("shopId", shopId).get()).getDocuments();
        return docs.stream().map(this::toRecord).map(ShopDocumentRecord::metadata)
                .sorted(java.util.Comparator.comparing(ShopDocumentMetadata::uploadedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    public Optional<ShopDocumentRecord> findById(String documentId) {
        DocumentSnapshot snapshot = FirestoreSupport.await(firebase.firestore().collection(COLLECTION).document(documentId).get());
        return snapshot.exists() ? Optional.of(toRecord(snapshot)) : Optional.empty();
    }

    public List<ShopDocumentRecord> insertAll(String shopId, List<ShopDocumentCreate> documents) {
        if (documents.isEmpty()) return List.of();
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(shopId);
        List<DocumentReference> refs = documents.stream().map(ignored -> db.collection(COLLECTION).document()).toList();
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            if (!shopSnapshot.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
            long count = shopSnapshot.getLong("documentCount") == null ? 0 : shopSnapshot.getLong("documentCount");
            if (count + documents.size() > ShopDocumentPolicy.MAX_FILES_PER_REGISTRATION) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A shop registration can have no more than five verification documents.");
            }
            List<ShopDocumentRecord> result = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                ShopDocumentCreate input = documents.get(i);
                DocumentReference ref = refs.get(i);
                Map<String, Object> data = new HashMap<>();
                data.put("shopId", shopId);
                data.put("documentType", input.documentType());
                data.put("originalFilename", input.originalFilename());
                data.put("storedFilename", input.storedFilename());
                data.put("contentType", input.contentType());
                data.put("sizeBytes", input.sizeBytes());
                data.put("uploadedBy", input.uploadedBy());
                data.put("uploadedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                tx.create(ref, data);
                result.add(new ShopDocumentRecord(ref.getId(), shopId, input.documentType(), input.originalFilename(),
                        input.storedFilename(), input.contentType(), input.sizeBytes(), input.uploadedBy(), java.time.LocalDateTime.now(java.time.Clock.systemUTC())));
            }
            tx.update(shop, "documentCount", count + documents.size(), "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            return List.copyOf(result);
        });
    }

    public boolean delete(String shopId, String documentId, String uploadedBy) {
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(shopId);
        DocumentReference document = db.collection(COLLECTION).document(documentId);
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot docSnapshot = FirestoreSupport.await(tx.get(document));
            if (!shopSnapshot.exists() || !docSnapshot.exists()) return false;
            if (!shopId.equals(docSnapshot.getString("shopId")) || !uploadedBy.equals(docSnapshot.getString("uploadedBy"))
                    || !"REJECTED".equals(shopSnapshot.getString("status"))) return false;
            long count = shopSnapshot.getLong("documentCount") == null ? 0 : shopSnapshot.getLong("documentCount");
            tx.delete(document);
            tx.update(shop, "documentCount", Math.max(0, count - 1), "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            return true;
        });
    }

    private ShopDocumentRecord toRecord(DocumentSnapshot snapshot) {
        return new ShopDocumentRecord(snapshot.getId(), snapshot.getString("shopId"), snapshot.getString("documentType"),
                snapshot.getString("originalFilename"), snapshot.getString("storedFilename"), snapshot.getString("contentType"),
                snapshot.getLong("sizeBytes") == null ? 0 : snapshot.getLong("sizeBytes"), snapshot.getString("uploadedBy"),
                FirestoreSupport.dateTime(snapshot.get("uploadedAt")));
    }
}
