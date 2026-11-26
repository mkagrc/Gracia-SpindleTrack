package com.spindletrack.auth;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OwnerOperationsService {
    private static final Logger log = LoggerFactory.getLogger(OwnerOperationsService.class);
    private static final String ORDERS = "orders";
    private static final String OUTBOX = "notificationOutbox";
    private final FirebaseAdminTokenVerifier firebase;

    public OwnerOperationsService(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }

    public record UnclaimedOrder(String id, String orderCode, String customerName, BigDecimal kilos,
            BigDecimal totalPrice, Instant readyAt, Instant unclaimedAt, String unit, String billingType,
            BigDecimal actualWeightKg) {
        @JsonProperty("quantity") public BigDecimal quantity() { return kilos; }
        @JsonProperty("quantityUnit") public String quantityUnit() { return unit; }
        public UnclaimedOrder(String id, String orderCode, String customerName, BigDecimal kilos,
                BigDecimal totalPrice, Instant readyAt, Instant unclaimedAt, String unit) {
            this(id, orderCode, customerName, kilos, totalPrice, readyAt, unclaimedAt, unit, OrderPricing.billingType(null, unit), null);
        }
    }
    public record ReadyOrder(String id, String orderCode, String customerName, BigDecimal kilos,
            BigDecimal totalPrice, String paymentStatus, Instant readyAt, boolean overdue, String unit,
            String billingType, BigDecimal actualWeightKg) {
        @JsonProperty("quantity") public BigDecimal quantity() { return kilos; }
        @JsonProperty("quantityUnit") public String quantityUnit() { return unit; }
        public ReadyOrder(String id, String orderCode, String customerName, BigDecimal kilos,
                BigDecimal totalPrice, String paymentStatus, Instant readyAt, boolean overdue, String unit) {
            this(id, orderCode, customerName, kilos, totalPrice, paymentStatus, readyAt, overdue, unit, OrderPricing.billingType(null, unit), null);
        }
    }
    public record ClaimedOrder(String id, String orderCode, String customerName, BigDecimal kilos,
            BigDecimal totalPrice, String paymentStatus, Instant claimedAt, String unit,
            String billingType, BigDecimal actualWeightKg) {
        @JsonProperty("quantity") public BigDecimal quantity() { return kilos; }
        @JsonProperty("quantityUnit") public String quantityUnit() { return unit; }
        public ClaimedOrder(String id, String orderCode, String customerName, BigDecimal kilos,
                BigDecimal totalPrice, String paymentStatus, Instant claimedAt, String unit) {
            this(id, orderCode, customerName, kilos, totalPrice, paymentStatus, claimedAt, unit, OrderPricing.billingType(null, unit), null);
        }
    }
    public record ReadyAndClaimedResult(List<ReadyOrder> unclaimed, List<ClaimedOrder> claimed) {}
    public record DailyMetric(String date, int orderCount, BigDecimal income) {}
    public record MonthlyMetric(String month, int orderCount, BigDecimal income) {}
    public record OwnerReport(DailyMetric today, MonthlyMetric thisMonth, List<DailyMetric> busiestDays) {}

    public List<UnclaimedOrder> unclaimed(FirebasePrincipal principal) {
        String shopId = unclaimedShop(principal);
        Firestore db = firebase.firestore();
        requireActive(db, shopId);
        QuerySnapshot snapshot = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("shopId", shopId).get());
        List<UnclaimedOrder> result = new ArrayList<>();
        for (DocumentSnapshot order : snapshot.getDocuments()) {
            if (!shopId.equals(order.getString("shopId")) || !Boolean.TRUE.equals(order.getBoolean("unclaimed"))
                    || !"READY".equals(order.getString("stage"))) continue;
            result.add(new UnclaimedOrder(order.getId(), order.getString("orderCode"), order.getString("customerName"),
                    orderQuantity(order), Money.fromFirestore(order.get("totalPriceCentavos"), order.get("totalPrice")),
                    instant(order.get("readyAt")), instant(order.get("unclaimedAt")), orderUnit(order),
                    orderBillingType(order), OrderPricing.decimal(order.get("actualWeightKg"))));
        }
        result.sort(Comparator.comparing(UnclaimedOrder::unclaimedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UnclaimedOrder::id));
        return result;
    }

    /**
     * Returns all READY orders (regardless of paymentStatus) as unclaimed,
     * and all CLAIMED orders, both scoped strictly to the caller's shop.
     * readyAt is taken from the stage history (READY entry), not from createdAt.
     * overdue = readyAt is 3 or more days ago (or unclaimedAt is 3+ days if readyAt absent).
     */
    public ReadyAndClaimedResult readyOrders(FirebasePrincipal principal) {
        String shopId = unclaimedShop(principal);
        Firestore db = firebase.firestore();
        requireActive(db, shopId);
        QuerySnapshot snapshot = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("shopId", shopId).get());
        QuerySnapshot historySnapshot = FirestoreSupport.await(db.collection("orderStageHistory").whereEqualTo("shopId", shopId).get());

        // Build a map of orderId -> READY changedAt and orderId -> CLAIMED changedAt from history
        Map<String, Instant> readyAtByOrder = new HashMap<>();
        Map<String, Instant> claimedAtByOrder = new HashMap<>();
        for (DocumentSnapshot row : historySnapshot.getDocuments()) {
            String stage = row.getString("stage");
            String orderId = row.getString("orderId");
            if (orderId == null) continue;
            Instant ts = instant(row.get("changedAt"));
            if ("READY".equals(stage)) {
                // Keep the latest READY entry if there are multiple
                readyAtByOrder.merge(orderId, ts == null ? Instant.EPOCH : ts, (a, b) -> a.isAfter(b) ? a : b);
            } else if ("CLAIMED".equals(stage)) {
                claimedAtByOrder.merge(orderId, ts == null ? Instant.EPOCH : ts, (a, b) -> a.isAfter(b) ? a : b);
            }
        }

        Instant threeDaysAgo = Instant.now().minusSeconds(3L * 24 * 60 * 60);
        List<ReadyOrder> unclaimed = new ArrayList<>();
        List<ClaimedOrder> claimed = new ArrayList<>();

        for (DocumentSnapshot order : snapshot.getDocuments()) {
            if (!shopId.equals(order.getString("shopId"))) continue;
            String stage = order.getString("stage");
            String id = order.getId();
            String orderCode = order.getString("orderCode");
            String customerName = order.getString("customerName");
            BigDecimal kilos = orderQuantity(order);
            BigDecimal totalPrice = orderPrice(order);
            String paymentStatus = normalizePaymentStatus(order.getString("paymentStatus"));
            String unit = orderUnit(order);
            String billingType = orderBillingType(order);
            BigDecimal actualWeightKg = OrderPricing.decimal(order.get("actualWeightKg"));

            if ("READY".equals(stage)) {
                Instant readyAt = readyAtByOrder.get(id);
                // Fall back to unclaimedAt then createdAt when history is absent
                if (readyAt == null || readyAt.equals(Instant.EPOCH)) {
                    readyAt = instant(order.get("unclaimedAt"));
                }
                if (readyAt == null) readyAt = instant(order.get("createdAt"));
                boolean overdue = readyAt != null && !readyAt.isAfter(threeDaysAgo);
                unclaimed.add(new ReadyOrder(id, orderCode, customerName, kilos, totalPrice,
                        paymentStatus, readyAt, overdue, unit, billingType, actualWeightKg));
            } else if ("CLAIMED".equals(stage)) {
                Instant claimedAt = claimedAtByOrder.get(id);
                if (claimedAt == null) claimedAt = instant(order.get("updatedAt"));
                claimed.add(new ClaimedOrder(id, orderCode, customerName, kilos, totalPrice,
                        paymentStatus, claimedAt, unit, billingType, actualWeightKg));
            }
        }

        // Unclaimed: oldest readyAt first (null last); Claimed: newest claimedAt first
        unclaimed.sort(Comparator.comparing(ReadyOrder::readyAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReadyOrder::id));
        claimed.sort(Comparator.comparing(ClaimedOrder::claimedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ClaimedOrder::id));

        return new ReadyAndClaimedResult(List.copyOf(unclaimed), List.copyOf(claimed));
    }

    public OwnerReport report(FirebasePrincipal principal) {        String shopId = ownerShop(principal);
        Firestore db = firebase.firestore();
        requireActive(db, shopId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime startDay = today.atStartOfDay();
        LocalDateTime startMonth = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthAfter = YearMonth.from(today).plusMonths(1).atDay(1).atStartOfDay();
        Map<LocalDate, Integer> dailyOrders = new HashMap<>();
        int todayOrders = 0, monthOrders = 0;
        QuerySnapshot orders = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("shopId", shopId).get());
        for (DocumentSnapshot order : orders.getDocuments()) {
            LocalDateTime created = FirestoreSupport.dateTime(order.get("createdAt"));
            if (created == null) continue;
            LocalDate date = created.toLocalDate();
            if (!date.isBefore(today.minusDays(29)) && !date.isAfter(today)) dailyOrders.merge(date, 1, Integer::sum);
            if (date.equals(today)) todayOrders++;
            if (!created.isBefore(startMonth) && created.isBefore(monthAfter)) monthOrders++;
        }
        BigDecimal todayIncome = BigDecimal.ZERO, monthIncome = BigDecimal.ZERO;
        QuerySnapshot payments = FirestoreSupport.await(db.collection("payments").whereEqualTo("shopId", shopId).get());
        for (DocumentSnapshot payment : payments.getDocuments()) {
            if (!"PAID".equals(payment.getString("status"))) continue;
            LocalDateTime paidAt = FirestoreSupport.dateTime(payment.get("paidAt"));
            if (paidAt == null) paidAt = FirestoreSupport.dateTime(payment.get("updatedAt"));
            if (paidAt == null) paidAt = FirestoreSupport.dateTime(payment.get("createdAt"));
            if (paidAt == null) continue;
            BigDecimal amount = Money.fromFirestore(payment.get("amountCentavos"), payment.get("amount"));
            if (paidAt.toLocalDate().equals(today)) todayIncome = todayIncome.add(amount);
            if (!paidAt.isBefore(startMonth) && paidAt.isBefore(monthAfter)) monthIncome = monthIncome.add(amount);
        }
        List<DailyMetric> busiest = dailyOrders.entrySet().stream().map(entry -> new DailyMetric(entry.getKey().toString(), entry.getValue(), BigDecimal.ZERO))
                .sorted(Comparator.comparingInt(DailyMetric::orderCount).reversed().thenComparing(DailyMetric::date))
                .limit(5).toList();
        return new OwnerReport(new DailyMetric(today.toString(), todayOrders, todayIncome),
                new MonthlyMetric(YearMonth.from(today).toString(), monthOrders, monthIncome), busiest);
    }

    @Scheduled(cron = "${spindletrack.unclaimed.cron:0 10 0 * * *}", zone = "UTC")
    public void scanUnclaimedDaily() {
        String correlationId = UUID.randomUUID().toString();
        try {
            Firestore db = firebase.firestore();
            QuerySnapshot ready = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("stage", "READY").get());
            LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(3);
            int marked = 0;
            for (QueryDocumentSnapshot candidate : ready.getDocuments()) {
                try {
                    LocalDateTime readyAt = FirestoreSupport.dateTime(candidate.get("readyAt"));
                    if (readyAt == null) readyAt = findReadyTime(db, candidate);
                    if (readyAt == null || readyAt.isAfter(cutoff)) continue;
                    if (markUnclaimed(db, candidate.getId(), candidate.getString("shopId"), readyAt)) marked++;
                } catch (RuntimeException exception) {
                    log.error("unclaimed_scan_order_failed correlationId={} errorType={}", correlationId,
                            exception.getClass().getSimpleName());
                }
            }
            if (marked > 0) log.info("unclaimed_scan_completed correlationId={} markedCount={}", correlationId, marked);
        } catch (RuntimeException exception) {
            log.error("scheduled_job_failed job=unclaimed-scan correlationId={} errorType={}",
                    correlationId, exception.getClass().getSimpleName());
        }
    }

    private boolean markUnclaimed(Firestore db, String orderId, String shopId, LocalDateTime readyAt) {
        if (shopId == null || shopId.isBlank()) return false;
        DocumentReference order = db.collection(ORDERS).document(orderId);
        String eventId = orderId + "_UNCLAIMED";
        DocumentReference event = db.collection(OUTBOX).document(eventId);
        String correlationId = UUID.randomUUID().toString();
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            if (!current.exists() || !shopId.equals(current.getString("shopId")) || !"READY".equals(current.getString("stage"))
                    || Boolean.TRUE.equals(current.getBoolean("unclaimed"))) return false;
            tx.update(order, Map.of("unclaimed", true, "unclaimedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            String customerId = current.getString("customerUserId");
            if (customerId != null && !customerId.isBlank()) {
                Map<String, Object> values = new HashMap<>();
                values.put("eventId", eventId); values.put("eventType", "UNCLAIMED"); values.put("shopId", shopId);
                values.put("correlationId", correlationId);
                values.put("orderId", orderId); values.put("customerUserId", customerId); values.put("status", "PENDING");
                values.put("attempts", 0); values.put("nextAttemptAtMillis", System.currentTimeMillis());
                values.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                tx.create(event, values);
            }
            return true;
        });
    }

    private static LocalDateTime findReadyTime(Firestore db, DocumentSnapshot order) {
        String shopId = order.getString("shopId");
        if (shopId == null) return null;
        QuerySnapshot history = FirestoreSupport.await(db.collection("orderStageHistory")
                .whereEqualTo("shopId", shopId).whereEqualTo("orderId", order.getId()).get());
        LocalDateTime readyAt = null;
        for (DocumentSnapshot row : history.getDocuments()) if ("READY".equals(row.getString("stage"))) {
            LocalDateTime changed = FirestoreSupport.dateTime(row.get("changedAt"));
            if (changed != null && (readyAt == null || changed.isAfter(readyAt))) readyAt = changed;
        }
        return readyAt;
    }

    private static void requireActive(Firestore db, String shopId) {
        ShopServiceCatalog.requireActiveShop(FirestoreSupport.await(db.collection("shops").document(shopId).get()));
    }
    private static String ownerShop(FirebasePrincipal principal) {
        if (principal == null || !"OWNER".equals(principal.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner access is required.");
        if (principal.shopId() == null || principal.shopId().isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a shop.");
        return principal.shopId();
    }
    private static String unclaimedShop(FirebasePrincipal principal) {
        if (principal == null || !List.of("OWNER", "STAFF").contains(principal.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner or staff access is required.");
        if (principal.shopId() == null || principal.shopId().isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a shop.");
        return principal.shopId();
    }
    private static BigDecimal decimal(Object value) {
        BigDecimal parsed = OrderPricing.decimal(value);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }
    private static Instant instant(Object value) {
        LocalDateTime dateTime = FirestoreSupport.dateTime(value);
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
    private static BigDecimal orderQuantity(DocumentSnapshot order) {
        Object quantityField = order.get("quantity");
        Object kilosField = order.get("kilos");
        Object raw = quantityField != null ? quantityField : kilosField;
        BigDecimal parsed = OrderPricing.decimal(raw);
        return parsed == null ? null : parsed.setScale(2, java.math.RoundingMode.HALF_UP);
    }
    private static BigDecimal orderPrice(DocumentSnapshot order) {
        return Money.fromFirestore(order.get("totalPriceCentavos"), order.get("totalPrice"));
    }
    private static String orderUnit(DocumentSnapshot order) {
        String unit = order.getString("quantityUnit");
        if (unit == null || unit.isBlank()) unit = order.getString("unit");
        return unit == null || unit.isBlank() ? "kg" : unit;
    }
    private static String orderBillingType(DocumentSnapshot order) {
        if (order.get("service") instanceof Map<?, ?> service && service.get("billingType") instanceof String configured && !configured.isBlank()) {
            return OrderPricing.billingType(configured, null);
        }
        String unit = order.getString("quantityUnit");
        if (unit == null || unit.isBlank()) unit = order.getString("unit");
        if (unit != null && !unit.isBlank()) return OrderPricing.billingType(null, unit);
        return order.get("kilos") != null || order.get("quantity") != null ? "PER_KG" : null;
    }
    private static String normalizePaymentStatus(String status) {
        return "PAID".equals(status) || "PENDING".equals(status) ? status : "UNPAID";
    }
}
