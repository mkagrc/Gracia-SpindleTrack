package com.spindletrack.auth;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.spindletrack.LaundryStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicOrderLookupService {
    private static final String GENERIC_FAILURE = "Order could not be found. Check the order code and mobile number.";
    private final FirebaseAdminTokenVerifier firebase;
    private final RegistrationRateLimitService rateLimits;
    private final ShopOrderService shopOrders;

    public PublicOrderLookupService(FirebaseAdminTokenVerifier firebase, RegistrationRateLimitService rateLimits) {
        this(firebase, rateLimits, null);
    }

    @Autowired
    public PublicOrderLookupService(FirebaseAdminTokenVerifier firebase, RegistrationRateLimitService rateLimits,
            ShopOrderService shopOrders) {
        this.firebase = firebase;
        this.rateLimits = rateLimits;
        this.shopOrders = shopOrders;
    }

    public record StageEvent(LaundryStage stage, Instant changedAt) {}
    public record WalkInOrder(String orderCode, String maskedMobile, LaundryStage currentStage,
            String paymentStatus, List<StageEvent> stageHistory, java.math.BigDecimal quantity,
            String unit, String quantityUnit, String billingType,
            String paymentMethodPreference, String paymentTiming) {
        public WalkInOrder(String orderCode, String maskedMobile, LaundryStage currentStage,
                String paymentStatus, List<StageEvent> stageHistory, java.math.BigDecimal quantity,
                String unit, String quantityUnit, String billingType) {
            this(orderCode, maskedMobile, currentStage, paymentStatus, stageHistory, quantity, unit,
                    quantityUnit, billingType, null, "PAY_NOW");
        }
        public WalkInOrder(String orderCode, String maskedMobile, LaundryStage currentStage,
                String paymentStatus, List<StageEvent> stageHistory, java.math.BigDecimal quantity, String unit) {
            this(orderCode, maskedMobile, currentStage, paymentStatus, stageHistory, quantity, unit,
                    unit, OrderPricing.billingType(null, unit));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicPayPalOrderRequest(String orderCode, String mobile, String clientRequestId) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicPayPalCaptureRequest(String orderCode, String mobile, String paypalOrderId) {}
    public record PublicPayPalOrderResponse(String orderCode, String paymentStatus, String paypalOrderId, String approvalUrl) {}
    public record PublicPayPalCaptureResponse(String orderCode, String paymentStatus, String paypalOrderId) {}

    private record MatchedOrder(String code, String normalizedMobile, String shopId, DocumentSnapshot orderSnapshot) {}

    public WalkInOrder lookup(String orderCode, String mobile, String remoteAddress) {
        MatchedOrder matched = resolveWalkInOrder(orderCode, mobile, remoteAddress);
        DocumentSnapshot match = matched.orderSnapshot();
        String shopId = matched.shopId();

        // Successful match — do NOT count toward the rate limit.
        String orderId = match.getId();
        Firestore db = firebase.firestore();
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
        return new WalkInOrder(matched.code(), maskMobile(matched.normalizedMobile()), parseStage(match.getString("stage")),
                paymentStatus(match.getString("paymentStatus")), List.copyOf(stages), parsedQuantity,
                quantityUnit, quantityUnit, billingType,
                paymentMethodPreference(match), paymentTiming(match));
    }

    public PublicPayPalOrderResponse createPublicPayPalOrder(PublicPayPalOrderRequest request, String remoteAddress) {
        String orderCode = request == null ? null : request.orderCode();
        String mobile = request == null ? null : request.mobile();
        MatchedOrder matched = resolveWalkInOrder(orderCode, mobile, remoteAddress);
        if (shopOrders == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Public PayPal checkout is unavailable.");
        }
        ShopOrderService.PayPalOrderResult created = shopOrders.createPublicPayPalOrder(
                matched.orderSnapshot().getId(),
                new ShopOrderService.PayPalOrderRequest(request == null ? null : request.clientRequestId()));
        String status = created.order() == null ? "PENDING" : created.order().paymentStatus();
        return new PublicPayPalOrderResponse(matched.code(), status, created.paypalOrderId(), created.approvalUrl());
    }

    public PublicPayPalCaptureResponse capturePublicPayPalOrder(PublicPayPalCaptureRequest request, String remoteAddress) {
        String orderCode = request == null ? null : request.orderCode();
        String mobile = request == null ? null : request.mobile();
        MatchedOrder matched = resolveWalkInOrder(orderCode, mobile, remoteAddress);
        if (shopOrders == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Public PayPal checkout is unavailable.");
        }
        ShopOrderService.OrderSummary summary = shopOrders.capturePublicPayPalOrder(
                matched.orderSnapshot().getId(),
                new ShopOrderService.PayPalCaptureRequest(request == null ? null : request.paypalOrderId()));
        String status = summary == null ? paymentStatus(matched.orderSnapshot().getString("paymentStatus")) : summary.paymentStatus();
        String paypalOrderId = summary == null ? null : summary.paymentLinkId();
        return new PublicPayPalCaptureResponse(matched.code(), status, paypalOrderId);
    }

    private MatchedOrder resolveWalkInOrder(String orderCode, String mobile, String remoteAddress) {
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
        return new MatchedOrder(code, normalizedMobile, shopId, match);
    }

    private static String paymentMethodPreference(DocumentSnapshot order) {
        String preference = order.getString("paymentMethodPreference");
        return "ONLINE".equals(preference) || "CASH".equals(preference) ? preference : null;
    }

    private static String paymentTiming(DocumentSnapshot order) {
        String timing = order.getString("paymentTiming");
        return timing == null || timing.isBlank() || "PAY_NOW".equals(timing) ? "PAY_NOW" : "PAY_AT_CLAIM";
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
