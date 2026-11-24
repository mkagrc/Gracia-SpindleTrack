package com.spindletrack.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OrderNotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OrderNotificationDispatcher.class);
    private static final long LEASE_MILLIS = 120_000L;
    private static final int MAX_ATTEMPTS = 10;
    private final FirebaseAdminTokenVerifier firebase;
    private final BrevoTransactionalEmailClient brevo;

    public OrderNotificationDispatcher(FirebaseAdminTokenVerifier firebase, BrevoTransactionalEmailClient brevo) {
        this.firebase = firebase; this.brevo = brevo;
    }

    @Scheduled(fixedDelayString = "${spindletrack.notifications.poll-ms:60000}")
    public void dispatchDue() {
        if (!brevo.configured()) return;
        String correlationId = UUID.randomUUID().toString();
        try { dispatchDueBatch(); }
        catch (RuntimeException exception) {
            log.error("scheduled_job_failed job=email-outbox correlationId={} errorType={}",
                    correlationId, exception.getClass().getSimpleName());
        }
    }

    private void dispatchDueBatch() {
        Firestore db = firebase.firestore();
        long now = System.currentTimeMillis();
        QuerySnapshot pending = FirestoreSupport.await(db.collection("notificationOutbox").whereEqualTo("status", "PENDING")
                .orderBy("nextAttemptAtMillis").limit(100).get());
        List<QueryDocumentSnapshot> due = new ArrayList<>(pending.getDocuments());
        QuerySnapshot sending = FirestoreSupport.await(db.collection("notificationOutbox").whereEqualTo("status", "SENDING").limit(100).get());
        for (QueryDocumentSnapshot row : sending.getDocuments()) {
            Long lease = row.getLong("leaseUntilMillis");
            if (lease == null || lease <= now) due.add(row);
        }
        due.sort(Comparator.comparing(row -> row.getLong("nextAttemptAtMillis") == null ? 0L : row.getLong("nextAttemptAtMillis")));
        for (QueryDocumentSnapshot row : due) {
            Long next = row.getLong("nextAttemptAtMillis");
            Long lease = row.getLong("leaseUntilMillis");
            if ("PENDING".equals(row.getString("status")) && next != null && next > now) continue;
            if ("SENDING".equals(row.getString("status")) && lease != null && lease > now) continue;
            dispatchOne(db, row.getId());
        }
    }

    private void dispatchOne(Firestore db, String eventId) {
        String claim = UUID.randomUUID().toString();
        DocumentReference event = db.collection("notificationOutbox").document(eventId);
        Boolean claimed = FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(event));
            if (!current.exists()) return false;
            String status = current.getString("status");
            long now = System.currentTimeMillis();
            Long retryAt = current.getLong("nextAttemptAtMillis");
            Long leaseUntil = current.getLong("leaseUntilMillis");
            if ("SENT".equals(status) || "SKIPPED".equals(status)) return false;
            if ("PENDING".equals(status) && retryAt != null && retryAt > now) return false;
            if ("SENDING".equals(status) && leaseUntil != null && leaseUntil > now) return false;
            long attempts = current.getLong("attempts") == null ? 0L : current.getLong("attempts");
            tx.update(event, Map.of("status", "SENDING", "claimToken", claim, "leaseUntilMillis", now + LEASE_MILLIS,
                    "attempts", attempts + 1));
            return true;
        });
        if (!claimed) return;
        String correlationId = eventId;
        try {
            DocumentSnapshot row = FirestoreSupport.await(event.get());
            if (row.getString("correlationId") != null && !row.getString("correlationId").isBlank())
                correlationId = row.getString("correlationId");
            String shopId = row.getString("shopId"), orderId = row.getString("orderId"), customerId = row.getString("customerUserId");
            DocumentSnapshot order = FirestoreSupport.await(db.collection("orders").document(orderId).get());
            if ("UNCLAIMED".equals(row.getString("eventType")) && (!order.exists()
                    || !"READY".equals(order.getString("stage")) || !Boolean.TRUE.equals(order.getBoolean("unclaimed")))) {
                complete(db, event, claim, "SKIPPED", ""); return;
            }
            DocumentSnapshot customer = FirestoreSupport.await(db.collection("users").document(customerId).get());
            DocumentSnapshot shop = FirestoreSupport.await(db.collection("shops").document(shopId).get());
            String email = customer.getString("email");
            if (!customer.exists() || email == null || email.isBlank() || !shop.exists()) {
                complete(db, event, claim, "SKIPPED", ""); return;
            }
            String name = customer.getString("displayName");
            if (name == null || name.isBlank()) name = "Customer";
            String shopName = shop.getString("name");
            String code = order.getString("orderCode");
            String type = row.getString("eventType");
            boolean ready = "READY".equals(type);
            String subject = ready ? "Your laundry is ready for pickup" : "Reminder: laundry is waiting for pickup";
            String text = "Hello " + name + ", your order " + code + (ready ? " is ready" : " is still unclaimed")
                    + " at " + shopName + ". Please contact the shop if you need assistance.";
            String html = "<p>Hello " + escape(name) + ",</p><p>Your order <strong>" + escape(code) + "</strong> "
                    + (ready ? "is ready for pickup" : "is still waiting to be claimed") + " at <strong>" + escape(shopName)
                    + "</strong>.</p><p>Please contact the shop if you need assistance.</p>";
            String messageId = brevo.send(eventId, email, name, subject, html, text);
            complete(db, event, claim, "SENT", messageId);
            log.info("email_outbox_sent correlationId={}", correlationId);
        } catch (RuntimeException exception) {
            retry(db, event, eventId, claim, correlationId, exception);
        }
    }

    public void retryDeadLetter(FirebasePrincipal principal, String eventId) {
        if (principal == null || !"PLATFORM_ADMIN".equals(principal.role()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Platform administrator access is required.");
        if (eventId == null || eventId.isBlank() || eventId.contains("/"))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Invalid notification event reference.");
        Firestore db = firebase.firestore();
        DocumentReference event = db.collection("notificationOutbox").document(eventId);
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(event));
            if (!current.exists()) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Notification event was not found.");
            if (!"DEAD_LETTER".equals(current.getString("status"))) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Only a dead-letter notification can be retried.");
            tx.update(event, Map.of("status", "PENDING", "attempts", 0L,
                    "nextAttemptAtMillis", System.currentTimeMillis(), "claimToken", com.google.cloud.firestore.FieldValue.delete(),
                    "leaseUntilMillis", com.google.cloud.firestore.FieldValue.delete(),
                    "lastErrorType", com.google.cloud.firestore.FieldValue.delete()));
            return null;
        });
    }

    private static void complete(Firestore db, DocumentReference event, String claim, String status, String messageId) {
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(event));
            if (current.exists() && claim.equals(current.getString("claimToken"))) {
                tx.update(event, Map.of("status", status, "sentAt", com.google.cloud.firestore.FieldValue.serverTimestamp(),
                        "providerMessageId", messageId == null ? "" : messageId,
                        "claimToken", com.google.cloud.firestore.FieldValue.delete(), "leaseUntilMillis", com.google.cloud.firestore.FieldValue.delete()));
            }
            return null;
        });
    }

    private static void retry(Firestore db, DocumentReference event, String eventId, String claim,
            String correlationId, RuntimeException error) {
        try {
            FirestoreSupport.transact(db, tx -> {
                DocumentSnapshot current = FirestoreSupport.await(tx.get(event));
                if (!current.exists() || !claim.equals(current.getString("claimToken"))) return null;
                long attempts = current.getLong("attempts") == null ? 1L : current.getLong("attempts");
                long delay = Math.min(21_600_000L, 60_000L * (1L << (int) Math.min(8, Math.max(0, attempts - 1))));
                tx.update(event, Map.of("status", attempts >= MAX_ATTEMPTS ? "DEAD_LETTER" : "PENDING",
                        "nextAttemptAtMillis", System.currentTimeMillis() + delay,
                        "lastErrorType", error.getClass().getSimpleName(), "claimToken", com.google.cloud.firestore.FieldValue.delete(),
                        "leaseUntilMillis", com.google.cloud.firestore.FieldValue.delete()));
                return null;
            });
            log.warn("email_outbox_retry correlationId={} errorType={}", correlationId, error.getClass().getSimpleName());
        } catch (RuntimeException writeError) {
            log.error("email_outbox_recovery_failed correlationId={} errorType={}", correlationId, writeError.getClass().getSimpleName());
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
