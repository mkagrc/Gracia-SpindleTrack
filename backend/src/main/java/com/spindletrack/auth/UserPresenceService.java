package com.spindletrack.auth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserPresenceService {
    private static final int PAGE_SIZE = 20;
    private static final Set<String> ROLES = Set.of("PLATFORM_ADMIN", "OWNER", "STAFF", "CUSTOMER");
    private final FirebaseAdminTokenVerifier firebase;
    private final PresenceStore presence;

    public UserPresenceService(FirebaseAdminTokenVerifier firebase, PresenceStore presence) {
        this.firebase = firebase;
        this.presence = presence;
    }

    public record AdminUser(String email, String name, String role, boolean online, Long lastSeen) {}
    public record AdminPage(List<AdminUser> content, int page, int size, long totalElements) {}
    public record StaffPresence(String name, boolean online, Long lastSeen) {}

    public AdminPage listUsers(String role, int page) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be zero or greater.");
        String filter = role == null || role.isBlank() ? null : role.trim().toUpperCase(Locale.ROOT);
        if (filter != null && !ROLES.contains(filter)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown user role filter.");
        Firestore db = firebase.firestore();
        List<QueryDocumentSnapshot> docs = FirestoreSupport.await(db.collection("users").get()).getDocuments();
        List<QueryDocumentSnapshot> filtered = docs.stream().filter(doc -> ROLES.contains(doc.getString("role")))
                .filter(doc -> filter == null || filter.equals(doc.getString("role")))
                .sorted(Comparator.comparing(UserPresenceService::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
        Map<String, Map<String, PresenceStore.Entry>> groups = filtered.stream().map(doc -> doc.getString("role"))
                .filter(ROLES::contains).distinct().collect(Collectors.toMap(PresencePath::groupForRole, presence::readGroup));
        List<AdminUser> users = new ArrayList<>();
        for (QueryDocumentSnapshot doc : filtered) {
            String roleValue = doc.getString("role");
            Map<String, PresenceStore.Entry> group = groups.get(PresencePath.groupForRole(roleValue));
            PresenceStore.Entry state = group == null ? null : group.get(doc.getString("firebaseUid"));
            String email = "CUSTOMER".equals(roleValue) ? null : doc.getString("email");
            users.add(new AdminUser(email, name(doc), roleValue,
                    state != null && state.online(), state == null ? null : state.lastSeen()));
        }
        long start = (long) page * PAGE_SIZE;
        List<AdminUser> content = start >= users.size() ? List.of() : users.subList((int) start, (int) Math.min(users.size(), start + PAGE_SIZE));
        return new AdminPage(content, page, PAGE_SIZE, users.size());
    }

    public List<StaffPresence> staffForShop(String shopId) {
        if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This owner account is not linked to a shop.");
        Firestore db = firebase.firestore();
        List<QueryDocumentSnapshot> staff = FirestoreSupport.await(db.collection("users").whereEqualTo("shopId", shopId)
                .whereEqualTo("role", "STAFF").get()).getDocuments();
        Map<String, PresenceStore.Entry> live = presence.readGroup("staff");
        return staff.stream().map(doc -> {
            PresenceStore.Entry state = live.get(doc.getString("firebaseUid"));
            // Staff emails and other private account fields are deliberately omitted.
            return new StaffPresence(name(doc), state != null && state.online(), state == null ? null : state.lastSeen());
        }).sorted(Comparator.comparing(StaffPresence::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    private static String name(DocumentSnapshot doc) {
        String value = doc.getString("displayName");
        return value == null ? doc.getString("fullName") : value;
    }
}
