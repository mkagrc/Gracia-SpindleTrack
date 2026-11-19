package com.spindletrack.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.spindletrack.LaundryStage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopOrderService {
    private static final String SHOPS = "shops";
    private static final String SERVICES = "services";
    private static final String ORDERS = "orders";
    private static final String COUNTERS = "orderCounters";
    private static final String HISTORY = "orderStageHistory";
    private static final int PAGE_SIZE = 20;
    private final FirebaseAdminTokenVerifier firebase;

    public ShopOrderService(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }

    public record ItemRequest(@NotBlank(message = "Choose a service for every item.") String serviceId,
            @NotNull(message = "Quantity is required.") @Min(value = 1, message = "Quantity must be greater than zero.") Integer quantity) {}
    public record CreateOrderRequest(@NotBlank(message = "Customer name is required.") @Size(max = 120, message = "Customer name must be 120 characters or fewer.") String customerName,
            @NotBlank(message = "Customer mobile number is required.") @Size(max = 32, message = "Customer mobile number is too long.") String mobile,
            @NotEmpty(message = "Add at least one service item.") @Size(max = 50, message = "An order can contain no more than 50 items.") List<@Valid ItemRequest> items,
            @Size(max = 1000, message = "Notes must be 1000 characters or fewer.") String notes) {}
    public record OrderItem(String serviceId, String name, int quantity, String unit, BigDecimal unitPrice, BigDecimal lineTotal) {}
    public record OrderSummary(String id, String shopId, String orderCode, String customerName, String mobile,
            String customerUserId, List<OrderItem> items, BigDecimal total, String notes, LaundryStage stage,
            Instant createdAt, String createdBy) {}
    public record StageHistory(String id, LaundryStage stage, String changedBy, Instant changedAt) {}
    public record OrderDetail(OrderSummary order, List<StageHistory> history) {}
    public record OrdersPage(List<OrderSummary> content, int number, int size, long totalElements, int totalPages) {}
    private record BuiltItems(List<OrderItem> items, BigDecimal total, List<Map<String, Object>> persisted) {}

    public OrdersPage list(FirebasePrincipal principal, String stageValue, String search, int page) {
        requireRole(principal, "OWNER", "STAFF");
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be zero or greater.");
        LaundryStage stage = parseStage(stageValue);
        String shopId = requireShop(principal.shopId());
        Firestore db = firebase.firestore();
        requireActiveShop(FirestoreSupport.await(db.collection(SHOPS).document(shopId).get()));
        QuerySnapshot query = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("shopId", shopId).get());
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<OrderSummary> matches = new ArrayList<>();
        for (DocumentSnapshot snapshot : query.getDocuments()) {
            OrderSummary order = mapOrder(snapshot);
            if (stage != null && order.stage() != stage) continue;
            if (!needle.isEmpty() && !order.orderCode().toLowerCase(Locale.ROOT).contains(needle)
                    && !order.customerName().toLowerCase(Locale.ROOT).contains(needle)) continue;
            matches.add(order);
        }
        matches.sort(Comparator.comparing(OrderSummary::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OrderSummary::id));
        int from = (int) Math.min((long) page * PAGE_SIZE, matches.size());
        int to = Math.min(from + PAGE_SIZE, matches.size());
        int totalPages = matches.isEmpty() ? 0 : (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        return new OrdersPage(List.copyOf(matches.subList(from, to)), page, PAGE_SIZE, matches.size(), totalPages);
    }

    public OrderDetail get(FirebasePrincipal principal, String orderId) {
        requireRole(principal, "OWNER", "STAFF");
        String shopId = requireShop(principal.shopId());
        String id = requireId(orderId);
        Firestore db = firebase.firestore();
        requireActiveShop(FirestoreSupport.await(db.collection(SHOPS).document(shopId).get()));
        DocumentSnapshot snapshot = FirestoreSupport.await(db.collection(ORDERS).document(id).get());
        requireOwned(snapshot, shopId);
        QuerySnapshot historyRows = FirestoreSupport.await(db.collection(HISTORY)
                .whereEqualTo("shopId", shopId).whereEqualTo("orderId", id).get());
        List<StageHistory> history = new ArrayList<>();
        for (DocumentSnapshot row : historyRows.getDocuments()) history.add(mapHistory(row));
        history.sort(Comparator.comparing(StageHistory::changedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StageHistory::id));
        return new OrderDetail(mapOrder(snapshot), List.copyOf(history));
    }

    public OrderSummary create(FirebasePrincipal principal, CreateOrderRequest request) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        if (request.items().size() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An order can contain no more than 50 items.");
        String customerName = request.customerName().trim();
        String mobile = normalizeMobile(request.mobile());
        String notes = request.notes() == null ? "" : request.notes().trim();
        if (mobile.length() < 7 || mobile.length() > 15) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid mobile number with 7 to 15 digits.");
        }
        Set<String> serviceIds = new HashSet<>();
        for (ItemRequest item : request.items()) {
            requireId(item.serviceId());
            if (item.quantity() == null || item.quantity() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be greater than zero.");
            if (!serviceIds.add(item.serviceId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each service may appear only once in an order.");
        }

        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference counter = db.collection(COUNTERS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document();
        DocumentReference history = db.collection(HISTORY).document();
        List<DocumentReference> serviceRefs = request.items().stream()
                .map(item -> db.collection(SERVICES).document(item.serviceId())).toList();
        String createdBy = principal.userId() == null ? principal.uid() : principal.userId();
        Instant createdAt = Instant.now();

        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot counterSnapshot = FirestoreSupport.await(tx.get(counter));
            List<DocumentSnapshot> serviceSnapshots = new ArrayList<>();
            for (DocumentReference serviceRef : serviceRefs) serviceSnapshots.add(FirestoreSupport.await(tx.get(serviceRef)));
            requireActiveShop(shopSnapshot);
            long nextNumber = (counterSnapshot.exists() && counterSnapshot.getLong("lastNumber") != null
                    ? counterSnapshot.getLong("lastNumber") : 0L) + 1L;
            BuiltItems built = buildItems(request.items(), serviceSnapshots, shopId);
            String code = "O-" + String.format(Locale.ROOT, "%06d", nextNumber);
            Map<String, Object> orderValues = new HashMap<>();
            orderValues.put("shopId", shopId);
            orderValues.put("orderCode", code);
            orderValues.put("customerName", customerName);
            orderValues.put("mobile", mobile);
            orderValues.put("customerUserId", null);
            orderValues.put("items", built.persisted());
            orderValues.put("total", built.total().doubleValue());
            orderValues.put("notes", notes);
            orderValues.put("stage", LaundryStage.RECEIVED.name());
            orderValues.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            orderValues.put("createdBy", createdBy);
            tx.set(counter, Map.of("shopId", shopId, "lastNumber", nextNumber));
            tx.create(order, orderValues);
            tx.create(history, historyValues(shopId, order.getId(), LaundryStage.RECEIVED, createdBy));
            return new OrderSummary(order.getId(), shopId, code, customerName, mobile, null, built.items(),
                    built.total(), notes, LaundryStage.RECEIVED, createdAt, createdBy);
        });
    }

    public OrderSummary advance(FirebasePrincipal principal, String orderId) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String id = requireId(orderId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document(id);
        DocumentReference history = db.collection(HISTORY).document();
        String changedBy = principal.userId() == null ? principal.uid() : principal.userId();
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            requireActiveShop(shopSnapshot);
            requireOwned(current, shopId);
            LaundryStage stage = parseStage(current.getString("stage"));
            if (stage == LaundryStage.CLAIMED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already at the final stage, Claimed.");
            }
            LaundryStage next = stage.next();
            // TODO(Step 7): advancing to CLAIMED must require the order to be PAID.
            tx.update(order, Map.of("stage", next.name(), "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            tx.create(history, historyValues(shopId, id, next, changedBy));
            return replaceStage(mapOrder(current), next);
        });
    }

    private static BuiltItems buildItems(List<ItemRequest> requests, List<DocumentSnapshot> snapshots, String shopId) {
        List<OrderItem> result = new ArrayList<>();
        List<Map<String, Object>> persisted = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (int index = 0; index < requests.size(); index++) {
            ItemRequest request = requests.get(index);
            DocumentSnapshot service = snapshots.get(index);
            if (!service.exists() || !shopId.equals(service.getString("shopId"))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "A selected service was not found in this shop.");
            }
            if (!Boolean.TRUE.equals(service.getBoolean("active"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A selected service is no longer active. Refresh the service list and try again.");
            }
            Double storedPrice = service.getDouble("pricePerUnit");
            if (storedPrice == null && service.getLong("pricePerUnit") != null) storedPrice = service.getLong("pricePerUnit").doubleValue();
            if (storedPrice == null || storedPrice <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "A selected service has an invalid price.");
            BigDecimal unitPrice = BigDecimal.valueOf(storedPrice).setScale(2, RoundingMode.HALF_UP);
            int quantity = request.quantity();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            String name = service.getString("name");
            String unit = service.getString("unit");
            OrderItem item = new OrderItem(service.getId(), name, quantity, unit, unitPrice, lineTotal);
            result.add(item);
            Map<String, Object> itemMap = new HashMap<>();
            itemMap.put("serviceId", item.serviceId()); itemMap.put("name", name); itemMap.put("quantity", quantity);
            itemMap.put("unit", unit); itemMap.put("unitPrice", unitPrice.doubleValue()); itemMap.put("lineTotal", lineTotal.doubleValue());
            persisted.add(itemMap);
            total = total.add(lineTotal).setScale(2, RoundingMode.HALF_UP);
        }
        return new BuiltItems(List.copyOf(result), total, List.copyOf(persisted));
    }

    private static Map<String, Object> historyValues(String shopId, String orderId, LaundryStage stage, String changedBy) {
        return Map.of("shopId", shopId, "orderId", orderId, "stage", stage.name(), "changedBy", changedBy,
                "changedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
    }

    private static OrderSummary mapOrder(DocumentSnapshot snapshot) {
        List<OrderItem> items = new ArrayList<>();
        Object rawItems = snapshot.get("items");
        if (rawItems instanceof List<?> list) for (Object raw : list) if (raw instanceof Map<?, ?> map) items.add(mapItem(map));
        Object rawTotal = snapshot.get("total");
        BigDecimal total = decimal(rawTotal);
        Instant createdAt = instant(snapshot.get("createdAt"));
        String customerName = snapshot.getString("customerName");
        return new OrderSummary(snapshot.getId(), snapshot.getString("shopId"), snapshot.getString("orderCode"),
                customerName == null ? "" : customerName, snapshot.getString("mobile"), snapshot.getString("customerUserId"),
                List.copyOf(items), total, snapshot.getString("notes"), parseStage(snapshot.getString("stage")),
                createdAt, snapshot.getString("createdBy"));
    }

    private static OrderItem mapItem(Map<?, ?> map) {
        return new OrderItem(ObjectsToString.value(map.get("serviceId")), ObjectsToString.value(map.get("name")),
                integer(map.get("quantity")), ObjectsToString.value(map.get("unit")), decimal(map.get("unitPrice")), decimal(map.get("lineTotal")));
    }

    private static StageHistory mapHistory(DocumentSnapshot snapshot) {
        return new StageHistory(snapshot.getId(), parseStage(snapshot.getString("stage")), snapshot.getString("changedBy"), instant(snapshot.get("changedAt")));
    }

    private static OrderSummary replaceStage(OrderSummary order, LaundryStage stage) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(),
                order.customerUserId(), order.items(), order.total(), order.notes(), stage, order.createdAt(), order.createdBy());
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    private static int integer(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static Instant instant(Object value) {
        java.time.LocalDateTime dateTime = FirestoreSupport.dateTime(value);
        return dateTime == null ? null : dateTime.toInstant(java.time.ZoneOffset.UTC);
    }
    private static LaundryStage parseStage(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LaundryStage.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown laundry stage."); }
    }
    private static void requireOwned(DocumentSnapshot order, String shopId) {
        if (!order.exists() || !shopId.equals(order.getString("shopId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found in this shop.");
        }
    }
    private static void requireActiveShop(DocumentSnapshot shop) {
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        if (!"ACTIVE".equals(shop.getString("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ShopOperationPolicy.blockedMessage(shop.getString("status")));
        }
    }
    private static void requireRole(FirebasePrincipal principal, String... allowed) {
        if (principal == null || java.util.Arrays.stream(allowed).noneMatch(role -> role.equals(principal.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to access shop orders.");
        }
    }
    private static String requireShop(String shopId) {
        if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a shop.");
        return shopId;
    }
    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.contains("/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid order or service ID.");
        return id;
    }
    private static String normalizeMobile(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }

    private static final class ObjectsToString {
        private static String value(Object value) { return value == null ? "" : value.toString(); }
    }
}
