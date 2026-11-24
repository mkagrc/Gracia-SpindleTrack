package com.spindletrack.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PayMongoWebhookService {
    private static final Logger log = LoggerFactory.getLogger(PayMongoWebhookService.class);
    private static final String EVENT_COLLECTION = "paymongoWebhookEvents";
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300L;
    private static final long LEASE_MILLIS = 30_000L;
    private static final int MAX_ATTEMPTS = 10;

    private final FirebaseAdminTokenVerifier firebase;
    private final ShopOrderService orders;
    private final ObjectMapper json;
    private final String secret;
    private final boolean liveMode;

    public record Receipt(String eventId, boolean duplicate, boolean supportedEvent) {}

    public PayMongoWebhookService(FirebaseAdminTokenVerifier firebase, ShopOrderService orders, ObjectMapper json,
            @Value("${paymongo.webhook-secret:}") String secret,
            @Value("${paymongo.webhook-mode:test}") String mode) {
        this.firebase = firebase;
        this.orders = orders;
        this.json = json;
        this.secret = secret == null ? "" : secret.trim();
        this.liveMode = "live".equalsIgnoreCase(mode);
    }

    public Receipt receive(byte[] rawBody, String signatureHeader) {
        return receive(rawBody, signatureHeader, Instant.now().getEpochSecond());
    }

    public void retryDeadLetter(FirebasePrincipal principal, String eventId) {
        if (principal == null || !"PLATFORM_ADMIN".equals(principal.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrator access is required.");
        if (eventId == null || !eventId.matches("[A-Za-z0-9_-]{5,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook event reference.");
        Firestore db = firebase.firestore();
        DocumentReference reference = db.collection(EVENT_COLLECTION).document(eventId);
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(reference));
            if (!current.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook event was not found.");
            if (!"DEAD_LETTER".equals(current.getString("status")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a dead-letter webhook event can be retried.");
            tx.update(reference, Map.of("status", "PENDING", "attempts", 0L,
                    "nextAttemptAtMillis", System.currentTimeMillis(),
                    "claimToken", com.google.cloud.firestore.FieldValue.delete(),
                    "leaseUntilMillis", com.google.cloud.firestore.FieldValue.delete(),
                    "lastErrorType", com.google.cloud.firestore.FieldValue.delete()));
            return null;
        });
    }

    Receipt receive(byte[] rawBody, String signatureHeader, long nowEpochSeconds) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > 65_536)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook body.");
        verifySignature(rawBody, signatureHeader, nowEpochSeconds);
        JsonNode root;
        try { root = json.readTree(rawBody); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook body."); }

        JsonNode event = root.path("data");
        JsonNode attributes = event.path("attributes");
        String eventId = firstText(event.path("id"), root.path("id"));
        String eventType = firstText(attributes.path("type"), attributes.path("event_type"), root.path("type"));
        boolean eventIsLive = attributes.path("livemode").asBoolean(root.path("livemode").asBoolean(false));
        if (eventId.isBlank() || !eventId.matches("[A-Za-z0-9_-]{5,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook event reference.");
        if (eventIsLive != liveMode)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook mode does not match this server.");
        if (!"link.payment.paid".equals(eventType)) return new Receipt(eventId, false, false);

        String linkId = extractLinkId(attributes);
        if (!linkId.matches("[A-Za-z0-9_-]{5,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment link reference.");
        Firestore db = firebase.firestore();
        DocumentReference reference = db.collection(EVENT_COLLECTION).document(eventId);
        String correlationId = UUID.randomUUID().toString();
        Boolean inserted = FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot existing = FirestoreSupport.await(tx.get(reference));
            if (existing.exists()) return false;
            Map<String, Object> values = new HashMap<>();
            values.put("eventId", eventId);
            values.put("eventType", eventType);
            values.put("paymentLinkId", linkId);
            values.put("livemode", eventIsLive);
            values.put("correlationId", correlationId);
            values.put("status", "PENDING");
            values.put("attempts", 0L);
            values.put("nextAttemptAtMillis", System.currentTimeMillis());
            values.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.create(reference, values);
            return true;
        });
        return new Receipt(eventId, !Boolean.TRUE.equals(inserted), true);
    }

    @Scheduled(fixedDelayString = "${paymongo.webhook.poll-ms:5000}")
    public void processPending() {
        if (secret.isBlank()) return;
        String runId = UUID.randomUUID().toString();
        try {
            Firestore db = firebase.firestore();
            QuerySnapshot pending = FirestoreSupport.await(db.collection(EVENT_COLLECTION)
                    .whereEqualTo("status", "PENDING").limit(100).get());
            QuerySnapshot processing = FirestoreSupport.await(db.collection(EVENT_COLLECTION)
                    .whereEqualTo("status", "PROCESSING").limit(100).get());
            long now = System.currentTimeMillis();
            for (QueryDocumentSnapshot row : pending.getDocuments()) {
                Long due = row.getLong("nextAttemptAtMillis");
                if (due == null || due <= now) processOne(db, row.getId());
            }
            for (QueryDocumentSnapshot row : processing.getDocuments()) {
                Long lease = row.getLong("leaseUntilMillis");
                if (lease == null || lease <= now) processOne(db, row.getId());
            }
        } catch (RuntimeException exception) {
            log.error("scheduled_job_failed job=paymongo-webhook-recovery correlationId={} errorType={}",
                    runId, exception.getClass().getSimpleName());
        }
    }

    boolean verifySignature(byte[] rawBody, String signatureHeader, long nowEpochSeconds) {
        if (secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PayMongo webhook verification is not configured.");
        if (signatureHeader == null || signatureHeader.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PayMongo webhook signature.");
        Map<String, String> parts = new HashMap<>();
        for (String item : signatureHeader.split(",")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2) parts.put(pair[0].trim(), pair[1].trim());
        }
        String timestamp = parts.get("t");
        long signedAt;
        try { signedAt = Long.parseLong(timestamp); }
        catch (RuntimeException exception) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PayMongo webhook signature."); }
        if (signedAt < nowEpochSeconds - SIGNATURE_TOLERANCE_SECONDS || signedAt > nowEpochSeconds + SIGNATURE_TOLERANCE_SECONDS)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expired PayMongo webhook signature.");
        String received = liveMode ? parts.get("li") : parts.get("te");
        byte[] expected = hmac(timestamp, rawBody);
        byte[] supplied;
        try { supplied = hex(received); }
        catch (RuntimeException exception) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PayMongo webhook signature."); }
        if (!MessageDigest.isEqual(expected, supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PayMongo webhook signature.");
        return true;
    }

    private void processOne(Firestore db, String eventId) {
        String claim = UUID.randomUUID().toString();
        DocumentReference reference = db.collection(EVENT_COLLECTION).document(eventId);
        Boolean claimed = FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(reference));
            if (!current.exists() || "COMPLETED".equals(current.getString("status")) || "DEAD_LETTER".equals(current.getString("status"))) return false;
            Long lease = current.getLong("leaseUntilMillis");
            if ("PROCESSING".equals(current.getString("status")) && lease != null && lease > System.currentTimeMillis()) return false;
            Long attempts = current.getLong("attempts");
            Map<String, Object> update = new HashMap<>();
            update.put("status", "PROCESSING"); update.put("claimToken", claim);
            update.put("leaseUntilMillis", System.currentTimeMillis() + LEASE_MILLIS);
            update.put("attempts", (attempts == null ? 0L : attempts) + 1L);
            tx.update(reference, update);
            return true;
        });
        if (!Boolean.TRUE.equals(claimed)) return;
        DocumentSnapshot current = FirestoreSupport.await(reference.get());
        String correlationId = current.getString("correlationId");
        try {
            orders.confirmOnlinePaymentByLink(current.getString("paymentLinkId"));
            finish(db, reference, claim, "COMPLETED", null);
            log.info("paymongo_webhook_processed correlationId={}", correlationId);
        } catch (RuntimeException exception) {
            retry(db, reference, claim, correlationId, exception);
        }
    }

    private static void finish(Firestore db, DocumentReference reference, String claim, String status, String errorType) {
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(reference));
            if (current.exists() && claim.equals(current.getString("claimToken"))) {
                Map<String, Object> update = new HashMap<>();
                update.put("status", status);
                update.put("processedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                update.put("claimToken", com.google.cloud.firestore.FieldValue.delete());
                update.put("leaseUntilMillis", com.google.cloud.firestore.FieldValue.delete());
                if (errorType != null) update.put("lastErrorType", errorType);
                tx.update(reference, update);
            }
            return null;
        });
    }

    private static void retry(Firestore db, DocumentReference reference, String claim, String correlationId, RuntimeException error) {
        try {
            FirestoreSupport.transact(db, tx -> {
                DocumentSnapshot current = FirestoreSupport.await(tx.get(reference));
                if (!current.exists() || !claim.equals(current.getString("claimToken"))) return null;
                long attempts = current.getLong("attempts") == null ? 1L : current.getLong("attempts");
                boolean dead = attempts >= MAX_ATTEMPTS;
                long delay = Math.min(900_000L, 2_000L * (1L << (int) Math.min(8, Math.max(0, attempts - 1))));
                Map<String, Object> update = new HashMap<>();
                update.put("status", dead ? "DEAD_LETTER" : "PENDING");
                update.put("nextAttemptAtMillis", System.currentTimeMillis() + delay);
                update.put("lastErrorType", error.getClass().getSimpleName());
                update.put("claimToken", com.google.cloud.firestore.FieldValue.delete());
                update.put("leaseUntilMillis", com.google.cloud.firestore.FieldValue.delete());
                tx.update(reference, update);
                return null;
            });
            log.warn("paymongo_webhook_retry correlationId={} errorType={}", correlationId, error.getClass().getSimpleName());
        } catch (RuntimeException writeError) {
            log.error("paymongo_webhook_recovery_failed correlationId={} errorType={}",
                    correlationId, writeError.getClass().getSimpleName());
        }
    }

    private byte[] hmac(String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(rawBody);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PayMongo webhook verification failed.");
        }
    }

    private static String extractLinkId(JsonNode attributes) {
        JsonNode resource = attributes.path("data");
        String direct = resource.path("id").asText("");
        if (direct.startsWith("link_")) return direct;
        String nested = firstText(resource.path("attributes").path("payment_link").path("id"),
                resource.path("attributes").path("payment_link_id"), resource.path("attributes").path("link_id"),
                attributes.path("payment_link").path("id"), attributes.path("payment_link_id"));
        if (!nested.isBlank()) return nested;
        return direct;
    }

    private static String firstText(JsonNode... values) {
        for (JsonNode value : values) if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText().trim();
        return "";
    }

    private byte[] hex(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("bad signature");
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2).toLowerCase(Locale.ROOT), 16);
        return bytes;
    }
}
