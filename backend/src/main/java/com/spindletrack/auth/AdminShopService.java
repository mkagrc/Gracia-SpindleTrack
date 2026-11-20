package com.spindletrack.auth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminShopService {
    private static final int PAGE_SIZE = 20;
    private final FirebaseAdminTokenVerifier firebase;
    private final ShopDocumentRepository documents;

    public AdminShopService(FirebaseAdminTokenVerifier firebase, ShopDocumentRepository documents) {
        this.firebase = firebase;
        this.documents = documents;
    }

    public record ShopListItem(String id, String name, String ownerName, String ownerEmail,
            java.time.LocalDateTime registrationDate, String status, int staffCount, int orderCount) {}
    public record ShopStaff(String id, String name, String email, String status) {}
    public record ShopDetail(String id, String name, String address, String contactNumber,
            String status, String rejectionReason, java.time.LocalDateTime registrationDate,
            Owner owner, List<ShopStaff> staff, List<ShopDocumentMetadata> documents,
            int staffCount, int orderCount) {}
    public record Owner(String id, String name, String email) {}
    public record PageResult(List<ShopListItem> content, int page, int size, long totalElements) {}

    public PageResult list(String status, String search, int page) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be zero or greater.");
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null && !List.of("PENDING", "ACTIVE", "REJECTED", "DEACTIVATED").contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown shop status filter.");
        }
        Firestore db = firebase.firestore();
        Query query = db.collection("shops");
        if (normalizedStatus != null) query = query.whereEqualTo("status", normalizedStatus);
        List<QueryDocumentSnapshot> shopDocs = FirestoreSupport.await(query.get()).getDocuments();
        List<QueryDocumentSnapshot> userDocs = FirestoreSupport.await(db.collection("users").get()).getDocuments();
        Map<String, List<QueryDocumentSnapshot>> usersByShop = userDocs.stream()
                .filter(doc -> doc.getString("shopId") != null)
                .collect(Collectors.groupingBy(doc -> doc.getString("shopId")));
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<ShopListItem> filtered = new ArrayList<>();
        for (QueryDocumentSnapshot shop : shopDocs) {
            List<QueryDocumentSnapshot> attached = usersByShop.getOrDefault(shop.getId(), List.of());
            QueryDocumentSnapshot ownerDoc = attached.stream().filter(user -> "OWNER".equals(user.getString("role"))).findFirst().orElse(null);
            String ownerName = ownerDoc == null ? null : userName(ownerDoc);
            String ownerEmail = ownerDoc == null ? null : ownerDoc.getString("email");
            if (!term.isEmpty() && !contains(shop.getString("name"), term) && !contains(ownerName, term) && !contains(ownerEmail, term)) continue;
            int staffCount = (int) attached.stream().filter(user -> "STAFF".equals(user.getString("role"))).count();
            filtered.add(new ShopListItem(shop.getId(), shop.getString("name"), ownerName, ownerEmail,
                    FirestoreSupport.dateTime(shop.get("createdAt")), shop.getString("status"), staffCount, 0));
        }
        filtered.sort(Comparator.comparingInt((ShopListItem item) -> "PENDING".equals(item.status()) ? 0 : 1)
                .thenComparing(ShopListItem::registrationDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ShopListItem::id));
        long total = filtered.size();
        long start = (long) page * PAGE_SIZE;
        List<ShopListItem> content = start >= total ? List.of() : filtered.subList((int) start,
                (int) Math.min(total, start + PAGE_SIZE));
        List<ShopListItem> counted = content.stream().map(item -> new ShopListItem(item.id(), item.name(),
                item.ownerName(), item.ownerEmail(), item.registrationDate(), item.status(), item.staffCount(),
                countOrders(db, item.id()))).toList();
        return new PageResult(counted, page, PAGE_SIZE, total);
    }

    public ShopDetail detail(String id) {
        Firestore db = firebase.firestore();
        DocumentSnapshot shop = FirestoreSupport.await(db.collection("shops").document(id).get());
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        List<QueryDocumentSnapshot> attached = FirestoreSupport.await(db.collection("users").whereEqualTo("shopId", id).get()).getDocuments();
        QueryDocumentSnapshot ownerDoc = attached.stream().filter(user -> "OWNER".equals(user.getString("role"))).findFirst().orElse(null);
        Owner owner = ownerDoc == null ? null : new Owner(ownerDoc.getId(), userName(ownerDoc), ownerDoc.getString("email"));
        List<ShopStaff> staff = attached.stream().filter(user -> "STAFF".equals(user.getString("role")))
                .map(user -> new ShopStaff(user.getId(), userName(user), user.getString("email"),
                        Boolean.TRUE.equals(user.getBoolean("active")) ? "ACTIVE" : "INACTIVE"))
                .sorted(Comparator.comparing(ShopStaff::name, Comparator.nullsLast(String::compareToIgnoreCase))).toList();
        return new ShopDetail(id, shop.getString("name"), shop.getString("address"), shop.getString("contactNumber"),
                shop.getString("status"), shop.getString("rejectionReason"), FirestoreSupport.dateTime(shop.get("createdAt")),
                owner, staff, documents.findMetadataForShop(id), staff.size(), countOrders(db, id));
    }

    public void transition(String shopId, String target, String reason, String adminId) {
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection("shops").document(shopId);
        DocumentReference history = db.collection("shopReviewHistory").document();
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot snapshot = FirestoreSupport.await(tx.get(shop));
            if (!snapshot.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
            String current = snapshot.getString("status");
            String expected = switch (target) {
                case "ACTIVE" -> "DEACTIVATED";
                case "DEACTIVATED" -> "ACTIVE";
                case "REJECTED", "APPROVED" -> "PENDING";
                default -> throw new IllegalArgumentException("Unsupported transition target");
            };
            if (!Objects.equals(expected, current)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot " + target.toLowerCase(Locale.ROOT) + " a shop with status " + current + ".");
            String status = "APPROVED".equals(target) ? "ACTIVE" : target;
            String action = "ACTIVE".equals(target) ? "REACTIVATED" : "APPROVED".equals(target) ? "APPROVED" : target;
            Map<String, Object> update = new HashMap<>();
            update.put("status", status);
            update.put("rejectionReason", "REJECTED".equals(target) ? reason : null);
            update.put("reviewedBy", adminId);
            update.put("reviewedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.update(shop, update);
            Map<String, Object> audit = new HashMap<>();
            audit.put("shopId", shopId);
            audit.put("action", action);
            audit.put("reason", "REJECTED".equals(target) ? reason : null);
            audit.put("actedBy", adminId);
            audit.put("actedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.create(history, audit);
            return null;
        });
        // TODO: Send the owner a status-change email through Brevo when email integration is approved.
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    private static int countOrders(Firestore db, String shopId) {
        return FirestoreSupport.await(db.collection("orders").whereEqualTo("shopId", shopId).get()).getDocuments().size();
    }

    private static String userName(DocumentSnapshot user) {
        String displayName = user.getString("displayName");
        return displayName == null ? user.getString("fullName") : displayName;
    }
}
