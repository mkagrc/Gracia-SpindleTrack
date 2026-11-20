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
            BigDecimal totalPrice, Instant readyAt, Instant unclaimedAt) {}
    public record DailyMetric(String date, int orderCount, BigDecimal income) {}
    public record MonthlyMetric(String month, int orderCount, BigDecimal income) {}
    public record OwnerReport(DailyMetric today, MonthlyMetric thisMonth, List<DailyMetric> busiestDays) {}

    public List<UnclaimedOrder> unclaimed(FirebasePrincipal principal) {
        String shopId = ownerShop(principal);
        Firestore db = firebase.firestore();
        requireActive(db, shopId);
        QuerySnapshot snapshot = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("shopId", shopId).get());
        List<UnclaimedOrder> result = new ArrayList<>();
        for (DocumentSnapshot order : snapshot.getDocuments()) {
            if (!Boolean.TRUE.equals(order.getBoolean("unclaimed")) || !"READY".equals(order.getString("stage"))) continue;
            result.add(new UnclaimedOrder(order.getId(), order.getString("orderCode"), order.getString("customerName"),
                    decimal(order.get("kilos")), decimal(order.get("totalPrice")), instant(order.get("readyAt")), instant(order.get("unclaimedAt"))));
        }
        result.sort(Comparator.comparing(UnclaimedOrder::unclaimedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UnclaimedOrder::id));
        return result;
    }

    public OwnerReport report(FirebasePrincipal principal) {
        String shopId = ownerShop(principal);
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
            BigDecimal amount = decimal(payment.get("amount"));
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
        Firestore db = firebase.firestore();
        QuerySnapshot ready = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("stage", "READY").get());
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(3);
        int marked = 0;
        for (QueryDocumentSnapshot candidate : ready.getDocuments()) {
            LocalDateTime readyAt = FirestoreSupport.dateTime(candidate.get("readyAt"));
            if (readyAt == null) readyAt = findReadyTime(db, candidate);
            if (readyAt == null || readyAt.isAfter(cutoff)) continue;
            if (markUnclaimed(db, candidate.getId(), candidate.getString("shopId"), readyAt)) marked++;
        }
        if (marked > 0) log.info("unclaimed_scan_marked count={}", marked);
    }

    private boolean markUnclaimed(Firestore db, String orderId, String shopId, LocalDateTime readyAt) {
        if (shopId == null || shopId.isBlank()) return false;
        DocumentReference order = db.collection(ORDERS).document(orderId);
        String eventId = orderId + "_UNCLAIMED";
        DocumentReference event = db.collection(OUTBOX).document(eventId);
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            if (!current.exists() || !shopId.equals(current.getString("shopId")) || !"READY".equals(current.getString("stage"))
                    || Boolean.TRUE.equals(current.getBoolean("unclaimed"))) return false;
            tx.update(order, Map.of("unclaimed", true, "unclaimedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            String customerId = current.getString("customerUserId");
            if (customerId != null && !customerId.isBlank()) {
                Map<String, Object> values = new HashMap<>();
                values.put("eventId", eventId); values.put("eventType", "UNCLAIMED"); values.put("shopId", shopId);
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
    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal number) return number;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return BigDecimal.ZERO;
    }
    private static Instant instant(Object value) {
        LocalDateTime dateTime = FirestoreSupport.dateTime(value);
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
