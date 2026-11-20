package com.spindletrack.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.spindletrack.LaundryStage;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopOrderService {
    private static final String SHOPS = "shops";
    private static final String SERVICES = "services";
    private static final String ORDERS = "orders";
    private static final String COUNTERS = "orderCounters";
    private static final String HISTORY = "orderStageHistory";
    private static final String PRICE_LOG = "priceLog";
    private static final String PAYMENTS = "payments";
    private static final int PAGE_SIZE = 20;
    private static final BigDecimal MAX_KILOS = new BigDecimal("999.99");
    private static final BigDecimal MAX_PRICE = new BigDecimal("999999.99");
    private final FirebaseAdminTokenVerifier firebase;
    private final PayMongoGateway payMongo;
    private final UserAccountRepository userAccounts;

    public ShopOrderService(FirebaseAdminTokenVerifier firebase) { this(firebase, null, null); }
    public ShopOrderService(FirebaseAdminTokenVerifier firebase, PayMongoGateway payMongo) { this(firebase, payMongo, null); }
    @Autowired
    public ShopOrderService(FirebaseAdminTokenVerifier firebase, PayMongoGateway payMongo, UserAccountRepository userAccounts) {
        this.firebase = firebase;
        this.payMongo = payMongo;
        this.userAccounts = userAccounts;
    }

    public record CreateOrderRequest(@NotBlank(message = "Customer name is required.") @Size(max = 120, message = "Customer name must be 120 characters or fewer.") String customerName,
            @NotBlank(message = "Customer mobile number is required.") @Size(max = 32, message = "Customer mobile number is too long.") String mobile,
            @NotBlank(message = "Choose a service.") String serviceId,
            @NotNull(message = "Kilos are required.") @DecimalMin(value = "0.0", inclusive = false, message = "Kilos must be greater than zero.") @Digits(integer = 3, fraction = 2, message = "Kilos must be no more than 999.99 with at most 2 decimal places.") BigDecimal kilos,
            @NotNull(message = "Total price is required.") @DecimalMin(value = "0.0", message = "Total price cannot be negative.") @Digits(integer = 6, fraction = 2, message = "Total price must be no more than 999999.99 with at most 2 decimal places.") BigDecimal totalPrice,
            @Size(max = 1000, message = "Notes must be 1000 characters or fewer.") String notes,
            @Email(message = "Enter a valid customer email address.") @Size(max = 254, message = "Customer email is too long.") String customerEmail) {
        public CreateOrderRequest(String customerName, String mobile, String serviceId, BigDecimal kilos, BigDecimal totalPrice, String notes) {
            this(customerName, mobile, serviceId, kilos, totalPrice, notes, null);
        }
    }
    public record PriceChangeRequest(@NotNull(message = "Total price is required.") @DecimalMin(value = "0.0", message = "Total price cannot be negative.") @Digits(integer = 6, fraction = 2, message = "Total price must be no more than 999999.99 with at most 2 decimal places.") BigDecimal totalPrice) {}
    public record OrderSummary(String id, String shopId, String orderCode, String customerName, String mobile,
            String customerUserId, String serviceId, String serviceName, BigDecimal kilos, BigDecimal totalPrice,
            String notes, LaundryStage stage, Instant createdAt, String createdBy, String paymentStatus,
            String paymentMethod, Instant paidAt, String paidByUid, String paidByName, String paymentLinkId,
            String paymentLinkUrl, Long paymentAmountCentavos, String paymentOrderIdReference) {
        public OrderSummary(String id, String shopId, String orderCode, String customerName, String mobile,
                String customerUserId, String serviceId, String serviceName, BigDecimal kilos, BigDecimal totalPrice,
                String notes, LaundryStage stage, Instant createdAt, String createdBy, String paymentStatus,
                String paymentMethod, Instant paidAt, String paidByUid, String paidByName) {
            this(id, shopId, orderCode, customerName, mobile, customerUserId, serviceId, serviceName, kilos,
                    totalPrice, notes, stage, createdAt, createdBy, paymentStatus, paymentMethod, paidAt,
                    paidByUid, paidByName, null, null, null, null);
        }
    }
    public record PaymentLinkResult(OrderSummary order, String paymentLinkId, String paymentLinkUrl) {}
    public record StageHistory(String id, LaundryStage stage, String changedBy, String changedByName, Instant changedAt) {}
    public record PriceHistory(String id, BigDecimal previousPrice, BigDecimal newPrice, String changedByUid,
            String changedByName, Instant changedAt) {}
    public record OrderDetail(OrderSummary order, List<StageHistory> history, List<PriceHistory> priceLog) {}
    public record OrdersPage(List<OrderSummary> content, int number, int size, long totalElements, int totalPages) {}
    public record CustomerMatch(String name) {}
    public record CustomerOrderSummary(String id, String orderCode, String shopName, String serviceName,
            BigDecimal kilos, BigDecimal totalPrice, LaundryStage stage, String paymentStatus,
            String paymentMethod, Instant paidAt, Instant createdAt) {}
    public record CustomerStageEvent(LaundryStage stage, String changedByName, Instant changedAt) {}
    public record CustomerPaymentEvent(String status, String method, Instant occurredAt) {}
    public record CustomerOrdersPage(List<CustomerOrderSummary> content, int number, int size, long totalElements, int totalPages) {}
    public record CustomerOrderDetail(CustomerOrderSummary order, List<CustomerStageEvent> history,
            List<CustomerPaymentEvent> paymentHistory) {}

    public CustomerMatch findCustomerForOrder(FirebasePrincipal principal, String email) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String normalized = UserAccountRepository.normalizeEmail(email);
        if (normalized.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the customer's exact email address.");
        Firestore db = firebase.firestore();
        requireActiveShop(FirestoreSupport.await(db.collection(SHOPS).document(shopId).get()));
        if (userAccounts == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Customer account lookup is unavailable.");
        UserAccount customer = userAccounts.findVerifiedCustomerByEmail(normalized).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No verified customer account matches that email."));
        return new CustomerMatch(customer.fullName());
    }

    public CustomerOrdersPage listMyOrders(FirebasePrincipal principal, int page) {
        requireRole(principal, "CUSTOMER");
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be zero or greater.");
        String userId = requireUserId(principal);
        Firestore db = firebase.firestore();
        QuerySnapshot rows = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("customerUserId", userId).get());
        List<CustomerOrderSummary> matches = new ArrayList<>();
        for (DocumentSnapshot row : rows.getDocuments()) matches.add(customerSummary(db, row));
        matches.sort(Comparator.comparing(CustomerOrderSummary::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CustomerOrderSummary::id));
        int from = (int) Math.min((long) page * PAGE_SIZE, matches.size());
        int to = Math.min(from + PAGE_SIZE, matches.size());
        int totalPages = matches.isEmpty() ? 0 : (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        return new CustomerOrdersPage(List.copyOf(matches.subList(from, to)), page, PAGE_SIZE, matches.size(), totalPages);
    }

    public CustomerOrderDetail getMyOrder(FirebasePrincipal principal, String orderId) {
        requireRole(principal, "CUSTOMER");
        String userId = requireUserId(principal);
        String id = requireId(orderId);
        Firestore db = firebase.firestore();
        DocumentSnapshot order = FirestoreSupport.await(db.collection(ORDERS).document(id).get());
        if (!order.exists() || !userId.equals(order.getString("customerUserId")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found.");
        String shopId = order.getString("shopId");
        QuerySnapshot historyRows = FirestoreSupport.await(db.collection(HISTORY)
                .whereEqualTo("shopId", shopId).whereEqualTo("orderId", id).get());
        List<CustomerStageEvent> history = new ArrayList<>();
        for (DocumentSnapshot row : historyRows.getDocuments())
            history.add(new CustomerStageEvent(parseStage(row.getString("stage")), row.getString("changedByName"), instant(row.get("changedAt"))));
        history.sort(Comparator.comparing(CustomerStageEvent::changedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        QuerySnapshot paymentRows = FirestoreSupport.await(db.collection(PAYMENTS)
                .whereEqualTo("shopId", shopId).whereEqualTo("orderId", id).get());
        List<CustomerPaymentEvent> payments = new ArrayList<>();
        for (DocumentSnapshot row : paymentRows.getDocuments()) {
            String status = row.getString("status");
            String method = row.getString("method");
            if (status != null && method != null) payments.add(new CustomerPaymentEvent(status, method,
                    instant("PAID".equals(status) ? row.get("updatedAt") : row.get("createdAt"))));
        }
        payments.sort(Comparator.comparing(CustomerPaymentEvent::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new CustomerOrderDetail(customerSummary(db, order), List.copyOf(history), List.copyOf(payments));
    }

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
        DocumentReference orderRef = db.collection(ORDERS).document(id);
        DocumentSnapshot snapshot = FirestoreSupport.await(orderRef.get());
        requireOwned(snapshot, shopId);
        QuerySnapshot historyRows = FirestoreSupport.await(db.collection(HISTORY)
                .whereEqualTo("shopId", shopId).whereEqualTo("orderId", id).get());
        List<StageHistory> history = new ArrayList<>();
        for (DocumentSnapshot row : historyRows.getDocuments()) history.add(mapHistory(row));
        history.sort(Comparator.comparing(StageHistory::changedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StageHistory::id));
        QuerySnapshot priceRows = FirestoreSupport.await(orderRef.collection(PRICE_LOG).get());
        List<PriceHistory> prices = new ArrayList<>();
        for (DocumentSnapshot row : priceRows.getDocuments()) prices.add(mapPriceHistory(row));
        prices.sort(Comparator.comparing(PriceHistory::changedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PriceHistory::id));
        return new OrderDetail(mapOrder(snapshot), List.copyOf(history), List.copyOf(prices));
    }

    public OrderSummary create(FirebasePrincipal principal, CreateOrderRequest request) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String requestedCustomerName = request.customerName() == null ? "" : request.customerName().trim();
        String normalizedCustomerEmail = UserAccountRepository.normalizeEmail(request.customerEmail());
        UserAccount linkedCustomer = null;
        if (!normalizedCustomerEmail.isBlank()) {
            if (userAccounts == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Customer account lookup is unavailable.");
            linkedCustomer = userAccounts.findVerifiedCustomerByEmail(normalizedCustomerEmail).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No verified customer account matches that email."));
        }
        final UserAccount resolvedCustomer = linkedCustomer;
        String mobile = normalizeMobile(request.mobile());
        String serviceId = requireId(request.serviceId());
        BigDecimal kilos = validateKilos(request.kilos());
        BigDecimal price = validatePrice(request.totalPrice());
        String notes = request.notes() == null ? "" : request.notes().trim();
        if (mobile.length() < 7 || mobile.length() > 15) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid mobile number with 7 to 15 digits.");

        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference counter = db.collection(COUNTERS).document(shopId);
        DocumentReference service = db.collection(SERVICES).document(serviceId);
        DocumentReference customer = resolvedCustomer == null ? null : db.collection("users").document(resolvedCustomer.id());
        DocumentReference order = db.collection(ORDERS).document();
        DocumentReference stageHistory = db.collection(HISTORY).document();
        DocumentReference priceHistory = order.collection(PRICE_LOG).document();
        String changedBy = userId(principal);
        String changedByUid = principal.uid();
        String changedByName = displayName(principal);
        Instant createdAt = Instant.now();

        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot counterSnapshot = FirestoreSupport.await(tx.get(counter));
            DocumentSnapshot serviceSnapshot = FirestoreSupport.await(tx.get(service));
            DocumentSnapshot customerSnapshot = customer == null ? null : FirestoreSupport.await(tx.get(customer));
            requireActiveShop(shopSnapshot);
            requireActiveService(serviceSnapshot, shopId);
            if (customerSnapshot != null && (!customerSnapshot.exists()
                    || !Boolean.TRUE.equals(customerSnapshot.getBoolean("active"))
                    || !Boolean.TRUE.equals(customerSnapshot.getBoolean("emailVerified"))
                    || !"CUSTOMER".equals(customerSnapshot.getString("role"))
                    || !normalizedCustomerEmail.equals(customerSnapshot.getString("normalizedEmail")))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No verified customer account matches that email.");
            }
            String customerName = customerSnapshot == null ? requestedCustomerName
                    : firstNonBlank(customerSnapshot.getString("displayName"), customerSnapshot.getString("fullName"));
            if (customerName.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No verified customer account matches that email.");
            long nextNumber = (counterSnapshot.exists() && counterSnapshot.getLong("lastNumber") != null
                    ? counterSnapshot.getLong("lastNumber") : 0L) + 1L;
            String code = "O-" + String.format(Locale.ROOT, "%06d", nextNumber);
            String serviceName = serviceSnapshot.getString("name");
            Map<String, Object> orderValues = new HashMap<>();
            orderValues.put("shopId", shopId);
            orderValues.put("orderCode", code);
            orderValues.put("customerName", customerName);
            orderValues.put("mobile", mobile);
            orderValues.put("customerUserId", resolvedCustomer == null ? null : resolvedCustomer.id());
            orderValues.put("serviceId", serviceId);
            orderValues.put("serviceName", serviceName);
            orderValues.put("kilos", kilos);
            orderValues.put("totalPrice", price);
            orderValues.put("notes", notes);
            orderValues.put("stage", LaundryStage.RECEIVED.name());
            orderValues.put("paymentStatus", "UNPAID");
            orderValues.put("paymentMethod", null);
            orderValues.put("paidAt", null);
            orderValues.put("paidByUid", null);
            orderValues.put("paidByName", null);
            orderValues.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            orderValues.put("createdBy", changedBy);
            Map<String, Object> priceValues = priceHistoryValues(null, price, changedByUid, changedByName);
            tx.set(counter, Map.of("shopId", shopId, "lastNumber", nextNumber));
            tx.create(order, orderValues);
            tx.create(stageHistory, historyValues(shopId, order.getId(), LaundryStage.RECEIVED, changedBy, changedByName));
            tx.create(priceHistory, priceValues);
            return new OrderSummary(order.getId(), shopId, code, customerName, mobile, resolvedCustomer == null ? null : resolvedCustomer.id(), serviceId, serviceName,
                    kilos, price, notes, LaundryStage.RECEIVED, createdAt, changedBy, "UNPAID", null, null, null, null);
        });
    }

    public OrderSummary changePrice(FirebasePrincipal principal, String orderId, PriceChangeRequest request) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String id = requireId(orderId);
        BigDecimal newPrice = validatePrice(request == null ? null : request.totalPrice());
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document(id);
        DocumentReference log = order.collection(PRICE_LOG).document();
        String changedByUid = principal.uid();
        String changedByName = displayName(principal);
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            requireActiveShop(shopSnapshot);
            requireOwned(current, shopId);
            if (isPaymentLocked(current)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cancel the pending payment link or record its payment before changing the price.");
            BigDecimal previousPrice = decimal(current.get("totalPrice"));
            tx.update(order, Map.of("totalPrice", newPrice, "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            tx.create(log, priceHistoryValues(previousPrice, newPrice, changedByUid, changedByName));
            return replacePrice(mapOrder(current), newPrice);
        });
    }

    public OrderSummary recordCashPayment(FirebasePrincipal principal, String orderId) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String id = requireId(orderId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document(id);
        DocumentReference paymentRef = db.collection(PAYMENTS).document();
        String paidByUid = principal.uid();
        String paidByName = displayName(principal);
        Instant paidAt = Instant.now();
        DocumentSnapshot before = FirestoreSupport.await(order.get());
        requireOwned(before, shopId);
        String pendingLinkId = "PENDING".equals(paymentStatus(before)) ? before.getString("paymentLinkId") : null;
        if (pendingLinkId != null && !pendingLinkId.isBlank()) {
            PayMongoGateway.VerifiedPayment alreadyPaid = gateway().findPaidPayment(pendingLinkId);
            if (matchesStoredPayment(before, id, alreadyPaid)) {
                confirmOnlinePayment(principal, id);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already paid online.");
            }
            gateway().archivePaymentLink(pendingLinkId);
            if (matchesStoredPayment(FirestoreSupport.await(order.get()), id, gateway().findPaidPayment(pendingLinkId))) {
                confirmOnlinePayment(principal, id);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order was paid online before cash payment could be recorded.");
            }
        }
        OrderSummary result = FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            requireActiveShop(shopSnapshot);
            requireOwned(current, shopId);
            if (isPaid(current)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already paid.");
            if ("PENDING".equals(paymentStatus(current)) && !java.util.Objects.equals(pendingLinkId, current.getString("paymentLinkId")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The payment link changed. Refresh the order and try again.");
            Map<String, Object> update = new HashMap<>();
            update.put("paymentStatus", "PAID");
            update.put("paymentMethod", "CASH");
            update.put("paidAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            update.put("paidByUid", paidByUid);
            update.put("paidByName", paidByName);
            update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            clearOnlineLink(update);
            tx.update(order, update);
            if (current.getString("paymentRecordId") != null)
                tx.update(db.collection(PAYMENTS).document(current.getString("paymentRecordId")), Map.of(
                        "status", "SUPERSEDED", "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            tx.create(paymentRef, paymentRecord(shopId, id, decimal(current.get("totalPrice")), "CASH", "PAID", paidByUid, paidByName, null));
            return withPayment(mapOrder(current), "PAID", "CASH", paidAt, paidByUid, paidByName);
        });
        return result;
    }

    public PaymentLinkResult createPaymentLink(FirebasePrincipal principal, String orderId) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String id = requireId(orderId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document(id);
        DocumentSnapshot initialShop = FirestoreSupport.await(shop.get());
        DocumentSnapshot initialOrder = FirestoreSupport.await(order.get());
        requireActiveShop(initialShop);
        requireOwned(initialOrder, shopId);
        if (!"UNPAID".equals(paymentStatus(initialOrder)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A payment link can be created only for an unpaid order.");
        BigDecimal total = decimal(initialOrder.get("totalPrice"));
        long amountCentavos = total.movePointRight(2).longValueExact();
        if (amountCentavos < 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PayMongo payment links require a total of at least ₱1.00.");
        PayMongoGateway.CreatedLink link = gateway().createPaymentLink(amountCentavos, id, initialOrder.getString("orderCode"));
        if (link.amountCentavos() != amountCentavos || !"PHP".equals(link.currency())) {
            try { gateway().archivePaymentLink(link.id()); } catch (RuntimeException ignored) { }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayMongo returned a link with an unexpected amount or currency.");
        }
        DocumentReference payment = db.collection(PAYMENTS).document();
        String paymentRecordId = payment.getId();
        try {
            OrderSummary updated = FirestoreSupport.transact(db, tx -> {
                DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
                DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
                requireActiveShop(shopSnapshot);
                requireOwned(current, shopId);
                if (!"UNPAID".equals(paymentStatus(current)) || decimal(current.get("totalPrice")).compareTo(total) != 0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The order changed before its payment link was created. Refresh and try again.");
                Map<String, Object> values = new HashMap<>();
                values.put("paymentStatus", "PENDING"); values.put("paymentMethod", "ONLINE");
                values.put("paymentLinkId", link.id()); values.put("paymentLinkUrl", link.url());
                values.put("paymentAmountCentavos", amountCentavos); values.put("paymentOrderIdReference", id);
                values.put("paymentRecordId", paymentRecordId);
                values.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                tx.update(order, values);
                tx.create(payment, paymentRecord(shopId, id, total, "ONLINE", "PENDING", principal.uid(), displayName(principal), link.id()));
                return withLink(mapOrder(current), link.id(), link.url(), amountCentavos, id);
            });
            return new PaymentLinkResult(updated, link.id(), link.url());
        } catch (RuntimeException exception) {
            try { gateway().archivePaymentLink(link.id()); } catch (RuntimeException ignored) { }
            throw exception;
        }
    }

    public OrderSummary confirmOnlinePayment(FirebasePrincipal principal, String orderId) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String id = requireId(orderId);
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document(id);
        DocumentSnapshot before = FirestoreSupport.await(order.get());
        requireOwned(before, shopId);
        if (!"PENDING".equals(paymentStatus(before))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has no pending online payment.");
        PayMongoGateway.VerifiedPayment verified = gateway().findPaidPayment(before.getString("paymentLinkId"));
        if (!matchesStoredPayment(before, id, verified))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PayMongo has not confirmed a payment matching this order and amount.");
        String paymentRecordId = before.getString("paymentRecordId");
        Instant paidAt = Instant.now();
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            requireActiveShop(shopSnapshot); requireOwned(current, shopId);
            if (!"PENDING".equals(paymentStatus(current)) || !java.util.Objects.equals(before.getString("paymentLinkId"), current.getString("paymentLinkId"))
                    || !matchesStoredPayment(current, id, verified))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The pending payment changed before confirmation. Refresh the order.");
            Map<String, Object> update = new HashMap<>();
            update.put("paymentStatus", "PAID"); update.put("paymentMethod", "ONLINE");
            update.put("paidAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            update.put("paidByUid", "PAYMONGO"); update.put("paidByName", "PayMongo");
            update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.update(order, update);
            if (paymentRecordId != null) tx.update(db.collection(PAYMENTS).document(paymentRecordId), Map.of(
                    "status", "PAID", "externalPaymentId", verified.id(), "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return withPayment(mapOrder(current), "PAID", "ONLINE", paidAt, "PAYMONGO", "PayMongo");
        });
    }

    public OrderSummary cancelPaymentLink(FirebasePrincipal principal, String orderId) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId()); String id = requireId(orderId);
        Firestore db = firebase.firestore(); DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document(id);
        DocumentSnapshot before = FirestoreSupport.await(order.get()); requireOwned(before, shopId);
        if (!"PENDING".equals(paymentStatus(before))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has no pending payment link to cancel.");
        String linkId = before.getString("paymentLinkId");
        PayMongoGateway.VerifiedPayment verification = gateway().findPaidPayment(linkId);
        if (matchesStoredPayment(before, id, verification)) {
            confirmOnlinePayment(principal, id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment was completed and cannot be cancelled.");
        }
        gateway().archivePaymentLink(linkId);
        String recordId = before.getString("paymentRecordId");
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop)); DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            requireActiveShop(shopSnapshot); requireOwned(current, shopId);
            if (!"PENDING".equals(paymentStatus(current)) || !java.util.Objects.equals(linkId, current.getString("paymentLinkId")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The payment link changed before cancellation. Refresh the order.");
            Map<String, Object> update = new HashMap<>(); update.put("paymentStatus", "UNPAID"); update.put("paymentMethod", null);
            clearOnlineLink(update); update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()); tx.update(order, update);
            if (recordId != null) tx.update(db.collection(PAYMENTS).document(recordId), Map.of("status", "CANCELLED", "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return mapOrderAfterPaymentReset(current);
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
        String changedBy = userId(principal);
        String changedByName = displayName(principal);
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            requireActiveShop(shopSnapshot);
            requireOwned(current, shopId);
            LaundryStage stage = parseStage(current.getString("stage"));
            if (stage == LaundryStage.CLAIMED) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already at the final stage, Claimed.");
            LaundryStage next = stage.next();
            if (next == LaundryStage.CLAIMED && !isPaid(current)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order must be paid before it can be claimed");
            }
            tx.update(order, Map.of("stage", next.name(), "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            tx.create(history, historyValues(shopId, id, next, changedBy, changedByName));
            return replaceStage(mapOrder(current), next);
        });
    }

    private static void requireActiveService(DocumentSnapshot service, String shopId) {
        if (!service.exists() || !shopId.equals(service.getString("shopId"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected service was not found in this shop.");
        if (!Boolean.TRUE.equals(service.getBoolean("active"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected service is no longer active. Refresh the service list and try again.");
    }

    private static Map<String, Object> historyValues(String shopId, String orderId, LaundryStage stage, String changedBy, String changedByName) {
        Map<String, Object> values = new HashMap<>();
        values.put("shopId", shopId); values.put("orderId", orderId); values.put("stage", stage.name());
        values.put("changedBy", changedBy); values.put("changedByName", changedByName);
        values.put("changedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return values;
    }

    private static Map<String, Object> priceHistoryValues(BigDecimal previousPrice, BigDecimal newPrice, String uid, String name) {
        Map<String, Object> values = new HashMap<>();
        values.put("previousPrice", previousPrice); values.put("newPrice", newPrice);
        values.put("changedByUid", uid); values.put("changedByName", name);
        values.put("changedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return values;
    }

    private static Map<String, Object> paymentRecord(String shopId, String orderId, BigDecimal amount, String method,
            String status, String actorUid, String actorName, String linkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("shopId", shopId); values.put("orderId", orderId); values.put("amount", amount);
        values.put("method", method); values.put("status", status); values.put("createdByUid", actorUid);
        values.put("createdByName", actorName); values.put("paymentLinkId", linkId);
        values.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        values.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return values;
    }
    private static void clearOnlineLink(Map<String, Object> values) {
        values.put("paymentLinkId", com.google.cloud.firestore.FieldValue.delete());
        values.put("paymentLinkUrl", com.google.cloud.firestore.FieldValue.delete());
        values.put("paymentAmountCentavos", com.google.cloud.firestore.FieldValue.delete());
        values.put("paymentOrderIdReference", com.google.cloud.firestore.FieldValue.delete());
        values.put("paymentRecordId", com.google.cloud.firestore.FieldValue.delete());
    }

    private static CustomerOrderSummary customerSummary(Firestore db, DocumentSnapshot order) {
        String shopId = order.getString("shopId");
        String shopName = "Laundry shop";
        if (shopId != null && !shopId.isBlank()) {
            DocumentSnapshot shop = FirestoreSupport.await(db.collection(SHOPS).document(shopId).get());
            if (shop.exists() && shop.getString("name") != null && !shop.getString("name").isBlank()) shopName = shop.getString("name");
        }
        return new CustomerOrderSummary(order.getId(), order.getString("orderCode"), shopName,
                order.getString("serviceName"), decimal(order.get("kilos")), decimal(order.get("totalPrice")),
                parseStage(order.getString("stage")), paymentStatus(order), order.getString("paymentMethod"),
                instant(order.get("paidAt")), instant(order.get("createdAt")));
    }

    private static OrderSummary mapOrder(DocumentSnapshot snapshot) {
        String name = snapshot.getString("customerName");
        return new OrderSummary(snapshot.getId(), snapshot.getString("shopId"), snapshot.getString("orderCode"),
                name == null ? "" : name, snapshot.getString("mobile"), snapshot.getString("customerUserId"),
                snapshot.getString("serviceId"), snapshot.getString("serviceName"), decimal(snapshot.get("kilos")),
                decimal(snapshot.get("totalPrice")), snapshot.getString("notes"), parseStage(snapshot.getString("stage")),
                instant(snapshot.get("createdAt")), snapshot.getString("createdBy"), paymentStatus(snapshot),
                snapshot.getString("paymentMethod"), instant(snapshot.get("paidAt")), snapshot.getString("paidByUid"),
                snapshot.getString("paidByName"), snapshot.getString("paymentLinkId"), snapshot.getString("paymentLinkUrl"),
                snapshot.getLong("paymentAmountCentavos"), snapshot.getString("paymentOrderIdReference"));
    }

    private static StageHistory mapHistory(DocumentSnapshot snapshot) {
        return new StageHistory(snapshot.getId(), parseStage(snapshot.getString("stage")), snapshot.getString("changedBy"),
                snapshot.getString("changedByName"), instant(snapshot.get("changedAt")));
    }
    private static PriceHistory mapPriceHistory(DocumentSnapshot snapshot) {
        return new PriceHistory(snapshot.getId(), nullableDecimal(snapshot.get("previousPrice")), decimal(snapshot.get("newPrice")),
                snapshot.getString("changedByUid"), snapshot.getString("changedByName"), instant(snapshot.get("changedAt")));
    }
    private static OrderSummary replaceStage(OrderSummary order, LaundryStage stage) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), order.totalPrice(), order.notes(), stage, order.createdAt(), order.createdBy(),
                order.paymentStatus(), order.paymentMethod(), order.paidAt(), order.paidByUid(), order.paidByName(), order.paymentLinkId(),
                order.paymentLinkUrl(), order.paymentAmountCentavos(), order.paymentOrderIdReference());
    }
    private static OrderSummary replacePrice(OrderSummary order, BigDecimal price) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), price, order.notes(), order.stage(), order.createdAt(), order.createdBy(),
                order.paymentStatus(), order.paymentMethod(), order.paidAt(), order.paidByUid(), order.paidByName(), order.paymentLinkId(),
                order.paymentLinkUrl(), order.paymentAmountCentavos(), order.paymentOrderIdReference());
    }
    private static OrderSummary withPayment(OrderSummary order, String status, String method, Instant paidAt, String uid, String name) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), order.totalPrice(), order.notes(), order.stage(), order.createdAt(),
                order.createdBy(), status, method, paidAt, uid, name, order.paymentLinkId(), order.paymentLinkUrl(),
                order.paymentAmountCentavos(), order.paymentOrderIdReference());
    }
    private static OrderSummary withLink(OrderSummary order, String linkId, String url, long amount, String orderReference) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), order.totalPrice(), order.notes(), order.stage(), order.createdAt(),
                order.createdBy(), "PENDING", "ONLINE", order.paidAt(), order.paidByUid(), order.paidByName(), linkId, url, amount, orderReference);
    }
    private static OrderSummary mapOrderAfterPaymentReset(DocumentSnapshot current) {
        OrderSummary order = mapOrder(current);
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), order.totalPrice(), order.notes(), order.stage(), order.createdAt(),
                order.createdBy(), "UNPAID", null, null, null, null);
    }
    private static boolean matchesStoredPayment(DocumentSnapshot order, String orderId, PayMongoGateway.VerifiedPayment payment) {
        if (payment == null || !"PENDING".equals(paymentStatus(order))) return false;
        Long amount = order.getLong("paymentAmountCentavos");
        String reference = order.getString("paymentOrderIdReference");
        long orderTotal = decimal(order.get("totalPrice")).movePointRight(2).longValue();
        return ("paid".equalsIgnoreCase(payment.status()) || "succeeded".equalsIgnoreCase(payment.status()))
                && amount != null && amount.longValue() == payment.amountCentavos() && amount.longValue() == orderTotal
                && orderId.equals(reference) && orderId.equals(payment.orderIdReference());
    }
    private PayMongoGateway gateway() {
        if (payMongo == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "PayMongo test mode is not available in this service instance.");
        return payMongo;
    }
    private static boolean isPaid(DocumentSnapshot order) { return "PAID".equals(order.getString("paymentStatus")); }
    private static boolean isPaymentLocked(DocumentSnapshot order) { return "PAID".equals(paymentStatus(order)) || "PENDING".equals(paymentStatus(order)); }
    private static String paymentStatus(DocumentSnapshot order) {
        String status = order.getString("paymentStatus");
        return "PAID".equals(status) || "PENDING".equals(status) ? status : "UNPAID";
    }
    private static BigDecimal validateKilos(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(MAX_KILOS) > 0 || value.scale() > 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kilos must be greater than 0 and no more than 999.99, with at most 2 decimal places.");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
    private static BigDecimal validatePrice(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(MAX_PRICE) > 0 || value.scale() > 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total price must be from 0.00 to 999999.99, with at most 2 decimal places.");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.setScale(2, RoundingMode.HALF_UP);
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    private static BigDecimal nullableDecimal(Object value) { return value == null ? null : decimal(value); }
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
        if (!order.exists() || !shopId.equals(order.getString("shopId"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found in this shop.");
    }
    private static void requireActiveShop(DocumentSnapshot shop) {
        if (!shop.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        if (!"ACTIVE".equals(shop.getString("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, ShopOperationPolicy.blockedMessage(shop.getString("status")));
    }
    private static void requireRole(FirebasePrincipal principal, String... allowed) {
        if (principal == null || java.util.Arrays.stream(allowed).noneMatch(role -> role.equals(principal.role()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to access shop orders.");
    }
    private static String requireShop(String shopId) {
        if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a shop.");
        return shopId;
    }
    private static String requireUserId(FirebasePrincipal principal) {
        if (principal.userId() == null || principal.userId().isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not linked to a customer profile.");
        return principal.userId();
    }
    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.contains("/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid order or service ID.");
        return id;
    }
    private static String userId(FirebasePrincipal principal) { return principal.userId() == null ? principal.uid() : principal.userId(); }
    private static String displayName(FirebasePrincipal principal) { return principal.displayName() == null || principal.displayName().isBlank() ? "Staff" : principal.displayName().trim(); }
    private static String firstNonBlank(String first, String fallback) { return first == null || first.isBlank() ? (fallback == null ? "" : fallback.trim()) : first.trim(); }
    private static String normalizeMobile(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }
}
