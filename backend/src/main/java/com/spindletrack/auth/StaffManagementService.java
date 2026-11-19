package com.spindletrack.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StaffManagementService {
    private static final String SHOPS = "shops";
    private static final String USERS = "users";
    private static final String INVITATIONS = "staffInvitations";
    private final FirebaseAdminTokenVerifier firebase;
    private final PresenceStore presence;
    private final String frontendBaseUrl;

    public StaffManagementService(FirebaseAdminTokenVerifier firebase, PresenceStore presence,
            @Value("${spindletrack.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.firebase = firebase;
        this.presence = presence;
        this.frontendBaseUrl = trimTrailingSlash(frontendBaseUrl);
    }

    public record InviteRequest(@NotBlank(message = "An email address is required.") @Email(message = "Enter a valid email address.") String email) {}
    public record AcceptRequest(@NotBlank(message = "Invitation token is required.") String token) {}
    public record InvitationSummary(String id, String email, String status, Instant expiresAt, Instant createdAt) {}
    public record InvitationLink(InvitationSummary invitation, String inviteLink) {}
    public record StaffMember(String id, String firebaseUid, String name, String email, boolean active, boolean online, Long lastSeen) {}
    public record AcceptResult(String role, String shopId, String shopName, String shopStatus) {}
    private record AcceptOutcome(AcceptResult value, HttpStatus status, String error) {}

    public InvitationLink invite(String shopId, String invitedBy, String email) {
        String ownerShop = requireShop(shopId);
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An email address is required.");
        String rawToken = StaffInvitationToken.create();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(7, ChronoUnit.DAYS);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(ownerShop);
        DocumentReference invitation = db.collection(INVITATIONS).document();
        Map<String, Object> data = new HashMap<>();
        data.put("shopId", ownerShop);
        data.put("normalizedEmail", normalizedEmail);
        data.put("status", "PENDING");
        data.put("tokenHash", StaffInvitationToken.hash(rawToken));
        data.put("expiresAt", Timestamp.of(java.util.Date.from(expiresAt)));
        data.put("invitedBy", invitedBy);
        data.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        data.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            requireActiveShop(shopSnapshot);
            tx.create(invitation, data);
            return null;
        });
        // TODO(Step 5B): send the invitation link through Brevo after email delivery is approved/configured.
        return new InvitationLink(new InvitationSummary(invitation.getId(), normalizedEmail, "PENDING", expiresAt, now), link(rawToken));
    }

    public List<InvitationSummary> invitations(String shopId) {
        String ownerShop = requireShop(shopId);
        Firestore db = firebase.firestore();
        requireActiveShop(FirestoreSupport.await(db.collection(SHOPS).document(ownerShop).get()));
        QuerySnapshot results = FirestoreSupport.await(db.collection(INVITATIONS).whereEqualTo("shopId", ownerShop).get());
        Instant now = Instant.now();
        List<InvitationSummary> invitations = new ArrayList<>();
        for (DocumentSnapshot snapshot : results.getDocuments()) invitations.add(mapInvitation(snapshot, now));
        invitations.sort(Comparator.comparing(InvitationSummary::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(InvitationSummary::id));
        return invitations;
    }

    public InvitationLink resend(String shopId, String invitationId) {
        String ownerShop = requireShop(shopId);
        String rawToken = StaffInvitationToken.create();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(7, ChronoUnit.DAYS);
        String tokenHash = StaffInvitationToken.hash(rawToken);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(ownerShop);
        DocumentReference invitation = db.collection(INVITATIONS).document(requireId(invitationId));
        InvitationSummary summary = FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(invitation));
            requireActiveShop(shopSnapshot);
            requireOwnedInvitation(current, ownerShop);
            String status = current.getString("status");
            if ("ACCEPTED".equals(status) || "REVOKED".equals(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending or expired invitation can be resent.");
            }
            tx.update(invitation, Map.of("status", "PENDING", "tokenHash", tokenHash,
                    "expiresAt", Timestamp.of(java.util.Date.from(expiresAt)),
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return new InvitationSummary(current.getId(), current.getString("normalizedEmail"), "PENDING", expiresAt, now);
        });
        // TODO(Step 5B): send the replacement invitation link through Brevo after email delivery is approved/configured.
        return new InvitationLink(summary, link(rawToken));
    }

    public InvitationSummary revoke(String shopId, String invitationId) {
        String ownerShop = requireShop(shopId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(ownerShop);
        DocumentReference invitation = db.collection(INVITATIONS).document(requireId(invitationId));
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(invitation));
            requireActiveShop(shopSnapshot);
            requireOwnedInvitation(current, ownerShop);
            if (!"PENDING".equals(current.getString("status"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending invitation can be revoked.");
            }
            Instant expiresAt = instant(current.get("expiresAt"));
            String status = StaffInvitationToken.expired(expiresAt, Instant.now()) ? "EXPIRED" : "REVOKED";
            tx.update(invitation, Map.of("status", status, "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return mapInvitation(current, Instant.now(), status);
        });
    }

    public AcceptResult accept(String token, FirebasePrincipal principal) {
        if (principal == null || principal.userId() == null || principal.uid() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with a verified account before accepting an invitation.");
        }
        if (token == null || token.isBlank() || token.length() > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation token is missing or invalid.");
        }
        String normalizedEmail = normalizeEmail(principal.email());
        String tokenHash = StaffInvitationToken.hash(token);
        Firestore db = firebase.firestore();
        AcceptOutcome outcome = FirestoreSupport.transact(db, tx -> {
            QuerySnapshot tokenMatches = FirestoreSupport.await(tx.get(db.collection(INVITATIONS).whereEqualTo("tokenHash", tokenHash)));
            if (tokenMatches.isEmpty()) return new AcceptOutcome(null, HttpStatus.NOT_FOUND, "Invitation link is invalid or no longer current.");
            DocumentSnapshot invitationSnapshot = tokenMatches.getDocuments().get(0);
            DocumentReference invitation = invitationSnapshot.getReference();
            String status = invitationSnapshot.getString("status");
            if (!"PENDING".equals(status)) {
                String reason = "ACCEPTED".equals(status) ? "This invitation has already been used."
                        : "This invitation is no longer available (" + Objects.toString(status, "unknown").toLowerCase(Locale.ROOT) + ").";
                return new AcceptOutcome(null, HttpStatus.CONFLICT, reason);
            }
            Instant now = Instant.now();
            Instant expiresAt = instant(invitationSnapshot.get("expiresAt"));
            if (StaffInvitationToken.expired(expiresAt, now)) {
                tx.update(invitation, Map.of("status", "EXPIRED", "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
                return new AcceptOutcome(null, HttpStatus.GONE, "This invitation has expired. Ask the shop owner to resend it.");
            }
            if (!normalizeEmail(invitationSnapshot.getString("normalizedEmail")).equals(normalizedEmail)) {
                return new AcceptOutcome(null, HttpStatus.FORBIDDEN, "This invitation was sent to a different email address. Sign in with the invited verified email.");
            }
            String shopId = invitationSnapshot.getString("shopId");
            if (shopId == null || shopId.isBlank()) return new AcceptOutcome(null, HttpStatus.CONFLICT, "The invitation is not linked to a shop.");
            DocumentReference shop = db.collection(SHOPS).document(shopId);
            DocumentReference user = db.collection(USERS).document(principal.userId());
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot userSnapshot = FirestoreSupport.await(tx.get(user));
            if (!shopSnapshot.exists() || !"ACTIVE".equals(shopSnapshot.getString("status"))) {
                return new AcceptOutcome(null, HttpStatus.FORBIDDEN, "Shop operations are blocked because the invited shop is not ACTIVE.");
            }
            if (!userSnapshot.exists() || !Boolean.TRUE.equals(userSnapshot.getBoolean("active"))) {
                return new AcceptOutcome(null, HttpStatus.FORBIDDEN, "This account is inactive or unavailable.");
            }
            if (!normalizeEmail(userSnapshot.getString("normalizedEmail")).equals(normalizedEmail)) {
                return new AcceptOutcome(null, HttpStatus.FORBIDDEN, "The signed-in account email does not match its verified Firebase email.");
            }
            String role = userSnapshot.getString("role");
            String currentShop = userSnapshot.getString("shopId");
            if (!"CUSTOMER".equals(role) || (currentShop != null && !currentShop.isBlank())) {
                return new AcceptOutcome(null, HttpStatus.CONFLICT, "This account cannot join another shop. Only an unassigned customer can accept a staff invitation.");
            }
            tx.update(user, Map.of("role", "STAFF", "shopId", shopId,
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            tx.update(invitation, Map.of("status", "ACCEPTED", "acceptedBy", principal.uid(),
                    "acceptedAt", com.google.cloud.firestore.FieldValue.serverTimestamp(),
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return new AcceptOutcome(new AcceptResult("STAFF", shopId, shopSnapshot.getString("name"), "ACTIVE"), null, null);
        });
        if (outcome.error() != null) throw new ResponseStatusException(outcome.status(), outcome.error());
        firebase.setSessionClaims(principal.uid(), "STAFF", outcome.value().shopId());
        return outcome.value();
    }

    public List<StaffMember> staff(String shopId) {
        String ownerShop = requireShop(shopId);
        Firestore db = firebase.firestore();
        requireActiveShop(FirestoreSupport.await(db.collection(SHOPS).document(ownerShop).get()));
        QuerySnapshot users = FirestoreSupport.await(db.collection(USERS).whereEqualTo("shopId", ownerShop).get());
        Map<String, PresenceStore.Entry> presenceByUid = presence.readGroup("staff");
        List<StaffMember> result = new ArrayList<>();
        for (DocumentSnapshot user : users.getDocuments()) {
            if (!"STAFF".equals(user.getString("role"))) continue;
            String uid = user.getString("firebaseUid");
            PresenceStore.Entry entry = uid == null ? null : presenceByUid.get(uid);
            result.add(new StaffMember(user.getId(), uid, firstNonBlank(user.getString("displayName"), user.getString("fullName")),
                    firstNonBlank(user.getString("email"), user.getString("normalizedEmail")),
                    Boolean.TRUE.equals(user.getBoolean("active")), entry != null && entry.online(), entry == null ? null : entry.lastSeen()));
        }
        result.sort(Comparator.comparing(StaffMember::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(StaffMember::id));
        return result;
    }

    public StaffMember deactivateStaff(String shopId, String staffUserId) {
        String ownerShop = requireShop(shopId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(ownerShop);
        DocumentReference user = db.collection(USERS).document(requireId(staffUserId));
        StaffMember staff = FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(user));
            requireActiveShop(shopSnapshot);
            requireOwnedStaff(current, ownerShop);
            tx.update(user, Map.of("active", false, "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return mapStaff(current, false, null);
        });
        firebase.setSessionClaims(staff.firebaseUid(), "STAFF", ownerShop);
        return staff;
    }

    public StaffMember removeStaff(String shopId, String staffUserId) {
        String ownerShop = requireShop(shopId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(ownerShop);
        DocumentReference user = db.collection(USERS).document(requireId(staffUserId));
        StaffMember removed = FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(user));
            requireActiveShop(shopSnapshot);
            requireOwnedStaff(current, ownerShop);
            tx.update(user, Map.of("role", "CUSTOMER", "shopId", com.google.cloud.firestore.FieldValue.delete(),
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return mapStaff(current, Boolean.TRUE.equals(current.getBoolean("active")), null);
        });
        firebase.setSessionClaims(removed.firebaseUid(), "CUSTOMER", null);
        return removed;
    }

    private String link(String token) { return frontendBaseUrl + "/accept-invitation?token=" + token; }

    private static InvitationSummary mapInvitation(DocumentSnapshot snapshot, Instant now) {
        Instant expiresAt = instant(snapshot.get("expiresAt"));
        String status = "PENDING".equals(snapshot.getString("status")) && StaffInvitationToken.expired(expiresAt, now)
                ? "EXPIRED" : snapshot.getString("status");
        return mapInvitation(snapshot, now, status);
    }

    private static InvitationSummary mapInvitation(DocumentSnapshot snapshot, Instant ignored, String status) {
        Instant createdAt = instant(snapshot.get("createdAt"));
        return new InvitationSummary(snapshot.getId(), snapshot.getString("normalizedEmail"), status,
                instant(snapshot.get("expiresAt")), createdAt);
    }

    private static StaffMember mapStaff(DocumentSnapshot user, boolean active, PresenceStore.Entry entry) {
        String uid = user.getString("firebaseUid");
        return new StaffMember(user.getId(), uid, firstNonBlank(user.getString("displayName"), user.getString("fullName")),
                firstNonBlank(user.getString("email"), user.getString("normalizedEmail")), active,
                entry != null && entry.online(), entry == null ? null : entry.lastSeen());
    }

    private static void requireOwnedStaff(DocumentSnapshot staff, String shopId) {
        if (!staff.exists() || !"STAFF".equals(staff.getString("role")) || !shopId.equals(staff.getString("shopId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member was not found in this shop.");
        }
    }

    private static void requireOwnedInvitation(DocumentSnapshot invitation, String shopId) {
        if (!invitation.exists() || !shopId.equals(invitation.getString("shopId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation was not found in this shop.");
        }
    }

    private static void requireActiveShop(DocumentSnapshot shop) {
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        if (!"ACTIVE".equals(shop.getString("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ShopOperationPolicy.blockedMessage(shop.getString("status")));
        }
    }

    private static String requireShop(String shopId) {
        if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a shop.");
        return shopId;
    }
    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.contains("/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid identifier.");
        return id;
    }
    private static String normalizeEmail(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
    private static String firstNonBlank(String first, String fallback) { return first == null || first.isBlank() ? fallback : first; }
    private static Instant instant(Object value) {
        java.time.LocalDateTime dateTime = FirestoreSupport.dateTime(value);
        return dateTime == null ? null : dateTime.toInstant(java.time.ZoneOffset.UTC);
    }
    private static String trimTrailingSlash(String value) {
        String base = value == null || value.isBlank() ? "http://localhost:5173" : value.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }
}
