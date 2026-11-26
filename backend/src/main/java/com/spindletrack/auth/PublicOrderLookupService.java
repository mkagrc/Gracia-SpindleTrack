package com.spindletrack.auth;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.spindletrack.LaundryStage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicOrderLookupService {
    private static final String GENERIC_FAILURE = "Order could not be found. Check the order code and mobile number.";
    private final FirebaseAdminTokenVerifier firebase;
    private final RegistrationRateLimitService rateLimits;

    public PublicOrderLookupService(FirebaseAdminTokenVerifier firebase, RegistrationRateLimitService rateLimits) {
        this.firebase = firebase;
        this.rateLimits = rateLimits;
    }

    public record StageEvent(LaundryStage stage, Instant changedAt) {}
    public record WalkInOrder(String orderCode, String maskedMobile, LaundryStage currentStage,
            String paymentStatus, List<StageEvent> stageHistory, java.math.BigDecimal quantity,
            String unit, String quantityUnit, String billingType) {
        public WalkInOrder(String orderCode, String maskedMobile, LaundryStage currentStage,
                String paymentStatus, List<StageEvent> stageHistory, java.math.BigDecimal quantity, String unit) {
            this(orderCode, maskedMobile, currentStage, paymentStatus, stageHistory, quantity, unit,
                    unit, OrderPricing.billingType(null, unit));
        }
    }

    public WalkInOrder lookup(String orderCode, String mobile, String remoteAddress) {
        // Block already-exhausted IPs before any database work (brute-force guard).
        rateLimits.checkPublicOrderLookupLimit(remoteAddress);

        String code = orderCode == null ? "" : orderCode.trim().toUpperCase(Locale.ROOT);
        String normalizedMobile = normalizeMobile(mobile);
        if (!code.matches("[A-Z0-9-]{2,32}") || normalizedMobile.length() < 7 || normalizedMobile.length() > 15) {
            rateLimits.recordPublicOrderLookupFailure(remoteAddress);
            throw notFound();
        }

        Firestore db = firebase.firestore();
        QuerySnapshot rows = FirestoreSupport.await(db.collection("orders").whereEqualTo("orderCode", code).get());
        DocumentSnapshot match = null;
        for (DocumentSnapshot candidate : rows.getDocuments()) {
            if (normalizedMobile.equals(normalizeMobile(candidate.getString("mobile")))) {
                if (match != null) {
                    rateLimits.recordPublicOrderLookupFailure(remoteAddress);
                    throw notFound();
                }
                match = candidate;
            }
        }
        if (match == null) {
            rateLimits.recordPublicOrderLookupFailure(remoteAddress);
            throw notFound();
        }
        String shopId = match.getString("shopId");
        if (shopId == null || shopId.isBlank()) {
            rateLimits.recordPublicOrderLookupFailure(remoteAddress);
            throw notFound();
        }
        DocumentSnapshot shop = FirestoreSupport.await(db.collection("shops").document(shopId).get());
        if (!shop.exists() || !"ACTIVE".equals(shop.getString("status"))) {
            rateLimits.recordPublicOrderLookupFailure(remoteAddress);
            throw notFound();
        }

        // Successful match — do NOT count toward the rate limit.
        String orderId = match.getId();
        QuerySnapshot historyRows = FirestoreSupport.await(db.collection("orderStageHistory")
                .whereEqualTo("shopId", shopId).whereEqualTo("orderId", orderId).get());
        List<StageEvent> stages = new ArrayList<>();
        for (DocumentSnapshot row : historyRows.getDocuments()) {
            String stageName = row.getString("stage");
            try {
                stages.add(new StageEvent(LaundryStage.valueOf(stageName), instant(row.get("changedAt"))));
            } catch (RuntimeException ignored) {
                // Ignore malformed historical rows rather than returning unrelated/internal data.
            }
        }
        stages.sort(Comparator.comparing(StageEvent::changedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        Object rawQuantity = match.get("quantity");
        Object rawKilos = match.get("kilos");
        Object rawWeight = rawQuantity != null ? rawQuantity : rawKilos;
        java.math.BigDecimal parsedQuantity = OrderPricing.decimal(rawWeight);
        if (parsedQuantity != null) parsedQuantity = parsedQuantity.setScale(2, java.math.RoundingMode.HALF_UP);
        String quantityUnit = match.getString("quantityUnit");
        if (quantityUnit == null || quantityUnit.isBlank()) quantityUnit = match.getString("unit");
        if (quantityUnit == null || quantityUnit.isBlank()) quantityUnit = "kg";
        String billingType = null;
        if (match.get("service") instanceof java.util.Map<?, ?> svc && svc.get("billingType") instanceof String configured && !configured.isBlank()) {
            billingType = OrderPricing.billingType(configured, null);
        } else if (match.getString("quantityUnit") != null || match.getString("unit") != null) {
            billingType = OrderPricing.billingType(null, quantityUnit);
        } else if (parsedQuantity != null) {
            billingType = "PER_KG";
        }
        return new WalkInOrder(code, maskMobile(normalizedMobile), parseStage(match.getString("stage")),
                paymentStatus(match.getString("paymentStatus")), List.copyOf(stages), parsedQuantity,
                quantityUnit, quantityUnit, billingType);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, GENERIC_FAILURE);
    }

    private static String normalizeMobile(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String maskMobile(String digits) {
        int visible = Math.min(4, digits.length());
        return "*".repeat(Math.max(0, digits.length() - visible)) + digits.substring(digits.length() - visible);
    }

    private static LaundryStage parseStage(String value) {
        try { return LaundryStage.valueOf(value); }
        catch (RuntimeException exception) { throw notFound(); }
    }

    private static String paymentStatus(String value) {
        return "PAID".equals(value) || "PENDING".equals(value) ? value : "UNPAID";
    }

    private static Instant instant(Object value) {
        java.time.LocalDateTime dateTime = FirestoreSupport.dateTime(value);
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
