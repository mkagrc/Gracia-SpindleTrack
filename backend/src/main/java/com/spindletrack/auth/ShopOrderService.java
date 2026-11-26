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
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spindletrack.LaundryStage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    public record AddOnSelection(@NotBlank(message = "Choose an add-on.") String addOnId,
            @NotNull(message = "Add-on quantity is required.") @Min(value = 1, message = "Add-on quantity must be at least 1.") @Max(value = 999, message = "Add-on quantity must be no more than 999.") Integer quantity) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateOrderRequest(@NotBlank(message = "Customer name is required.") @Size(max = 120, message = "Customer name must be 120 characters or fewer.") String customerName,
            @NotBlank(message = "Customer mobile number is required.") @Size(max = 32, message = "Customer mobile number is too long.") String mobile,
            @NotBlank(message = "Choose a service.") String serviceId,
            @JsonAlias("kilos") @NotNull(message = "Quantity is required.") @DecimalMin(value = "0.0", inclusive = false, message = "Quantity must be greater than zero.") @Digits(integer = 3, fraction = 2, message = "Quantity must be no more than 999.99 with at most 2 decimal places.") BigDecimal quantity,
            @DecimalMin(value = "0.0", inclusive = false, message = "Actual weight must be greater than zero.") @Digits(integer = 3, fraction = 2, message = "Actual weight must be no more than 999.99 with at most 2 decimal places.") BigDecimal actualWeightKg,
            @Size(max = 20, message = "An order can contain at most 20 add-on rows.") List<@Valid AddOnSelection> addOns,
            @Size(max = 1000, message = "Notes must be 1000 characters or fewer.") String notes,
            @Email(message = "Enter a valid customer email address.") @Size(max = 254, message = "Customer email is too long.") String customerEmail,
            @NotBlank(message = "Choose CASH or ONLINE as the payment method.") String paymentMethodPreference,
            @NotBlank(message = "Choose PAY_NOW or PAY_AT_CLAIM as the payment timing.") String paymentTiming,
            @NotBlank(message = "A client request UUID is required.") String clientRequestId) {
        public CreateOrderRequest(String customerName, String mobile, String serviceId, BigDecimal quantity, BigDecimal ignoredTotal,
                String notes, String customerEmail) {
            this(customerName, mobile, serviceId, quantity, null, List.of(), notes, customerEmail, "CASH", "PAY_AT_CLAIM", UUID.randomUUID().toString());
        }
        public CreateOrderRequest(String customerName, String mobile, String serviceId, BigDecimal quantity, BigDecimal ignoredTotal, String notes) {
            this(customerName, mobile, serviceId, quantity, null, List.of(), notes, null, "CASH", "PAY_AT_CLAIM", UUID.randomUUID().toString());
        }
        public CreateOrderRequest(String customerName, String mobile, String serviceId, BigDecimal quantity, BigDecimal ignoredTotal,
                String notes, String customerEmail, String paymentMethodPreference, String paymentTiming, String clientRequestId) {
            this(customerName, mobile, serviceId, quantity, null, List.of(), notes, customerEmail,
                    paymentMethodPreference, paymentTiming, clientRequestId);
        }
    }
    public record PriceChangeRequest(@NotNull(message = "Total price is required.") @DecimalMin(value = "0.0", message = "Total price cannot be negative.") @Digits(integer = 6, fraction = 2, message = "Total price must be no more than 999999.99 with at most 2 decimal places.") BigDecimal totalPrice) {}
    public record ExpectedStageRequest(@NotBlank(message = "The current stage is required.") String expectedStage,
            @NotBlank(message = "A client request UUID is required.") String clientRequestId) {}
    public record PaymentLinkRequest(@NotBlank(message = "A client request UUID is required.") String clientRequestId) {}
    public record OrderSummary(String id, String shopId, String orderCode, String customerName, String mobile,
            String customerUserId, String serviceId, String serviceName, BigDecimal kilos, BigDecimal totalPrice,
            String notes, LaundryStage stage, Instant createdAt, String createdBy, String paymentStatus,
            String paymentMethod, Instant paidAt, String paidByUid, String paidByName, String paymentLinkId,
            String paymentLinkUrl, Long paymentAmountCentavos, String paymentOrderIdReference, String unit,
            String paymentMethodPreference, String paymentTiming, String billingType, BigDecimal actualWeightKg) {
        @JsonProperty("quantity") public BigDecimal quantity() { return kilos; }
        @JsonProperty("quantityUnit") public String quantityUnit() { return unit; }
        public OrderSummary(String id, String shopId, String orderCode, String customerName, String mobile,
                String customerUserId, String serviceId, String serviceName, BigDecimal kilos, BigDecimal totalPrice,
                String notes, LaundryStage stage, Instant createdAt, String createdBy, String paymentStatus,
                String paymentMethod, Instant paidAt, String paidByUid, String paidByName, String paymentLinkId,
                String paymentLinkUrl, Long paymentAmountCentavos, String paymentOrderIdReference, String unit,
                String paymentMethodPreference, String paymentTiming) {
            this(id, shopId, orderCode, customerName, mobile, customerUserId, serviceId, serviceName, kilos, totalPrice,
                    notes, stage, createdAt, createdBy, paymentStatus, paymentMethod, paidAt, paidByUid, paidByName,
                    paymentLinkId, paymentLinkUrl, paymentAmountCentavos, paymentOrderIdReference, unit,
                    paymentMethodPreference, paymentTiming, OrderPricing.billingType(null, unit), null);
        }
        public OrderSummary(String id, String shopId, String orderCode, String customerName, String mobile,
                String customerUserId, String serviceId, String serviceName, BigDecimal kilos, BigDecimal totalPrice,
                String notes, LaundryStage stage, Instant createdAt, String createdBy, String paymentStatus,
                String paymentMethod, Instant paidAt, String paidByUid, String paidByName, String paymentLinkId,
                String paymentLinkUrl, Long paymentAmountCentavos, String paymentOrderIdReference, String unit) {
            this(id, shopId, orderCode, customerName, mobile, customerUserId, serviceId, serviceName, kilos, totalPrice,
                    notes, stage, createdAt, createdBy, paymentStatus, paymentMethod, paidAt, paidByUid, paidByName,
                    paymentLinkId, paymentLinkUrl, paymentAmountCentavos, paymentOrderIdReference, unit, "CASH", "PAY_NOW");
        }
        public OrderSummary(String id, String shopId, String orderCode, String customerName, String mobile,
                String customerUserId, String serviceId, String serviceName, BigDecimal kilos, BigDecimal totalPrice,
                String notes, LaundryStage stage, Instant createdAt, String createdBy, String paymentStatus,
                String paymentMethod, Instant paidAt, String paidByUid, String paidByName, String paymentLinkId,
                String paymentLinkUrl, Long paymentAmountCentavos, String paymentOrderIdReference) {
            this(id, shopId, orderCode, customerName, mobile, customerUserId, serviceId, serviceName, kilos, totalPrice,
                    notes, stage, createdAt, createdBy, paymentStatus, paymentMethod, paidAt, paidByUid, paidByName,
                    paymentLinkId, paymentLinkUrl, paymentAmountCentavos, paymentOrderIdReference, "kg", "CASH", "PAY_NOW");
        }
        public OrderSummary(String id, String shopId, String orderCode, String customerName, String mobile,
                String customerUserId, String serviceId, String serviceName, BigDecimal kilos, BigDecimal totalPrice,
                String notes, LaundryStage stage, Instant createdAt, String createdBy, String paymentStatus,
                String paymentMethod, Instant paidAt, String paidByUid, String paidByName) {
            this(id, shopId, orderCode, customerName, mobile, customerUserId, serviceId, serviceName, kilos, totalPrice,
                    notes, stage, createdAt, createdBy, paymentStatus, paymentMethod, paidAt, paidByUid, paidByName,
                    null, null, null, null, "kg", "CASH", "PAY_NOW");
        }
    }
    public record PaymentLinkResult(OrderSummary order, String paymentLinkId, String paymentLinkUrl) {}
    public record StageHistory(String id, LaundryStage stage, String changedBy, String changedByName, Instant changedAt) {}
    public record PriceHistory(String id, BigDecimal previousPrice, BigDecimal newPrice, String changedByUid,
            String changedByName, Instant changedAt) {}
    public record ServiceSnapshot(String id, String name, String category, String billingType,
            BigDecimal unitPrice, BigDecimal capacityKg, String notes) {}
    public record OrderAddOnLine(String id, String name, String unit, BigDecimal unitPrice,
            int quantity, BigDecimal lineTotal) {}
    public record Invoice(String shopName, String shopContactNumber, String orderCode, Instant createdAt,
            String customerName, ServiceSnapshot service, BigDecimal quantity, String quantityUnit,
            BigDecimal actualWeightKg, List<OrderAddOnLine> addOns, BigDecimal serviceSubtotal,
            BigDecimal addOnsSubtotal, BigDecimal total, String paymentMethod, String paymentTiming,
            String paymentStatus, Instant paidAt) {}
    public record OrderDetail(OrderSummary order, List<StageHistory> history, List<PriceHistory> priceLog, Invoice invoice) {
        public OrderDetail(OrderSummary order, List<StageHistory> history, List<PriceHistory> priceLog) {
            this(order, history, priceLog, null);
        }
    }
    public record OrdersPage(List<OrderSummary> content, int number, int size, long totalElements, int totalPages) {}
    public record CustomerMatch(String name) {}
    public record CustomerOrderSummary(String id, String orderCode, String shopName, String serviceName,
            BigDecimal kilos, BigDecimal totalPrice, LaundryStage stage, String paymentStatus,
            String paymentMethod, Instant paidAt, Instant createdAt, String unit,
            String billingType, BigDecimal actualWeightKg) {
        @JsonProperty("quantity") public BigDecimal quantity() { return kilos; }
        @JsonProperty("quantityUnit") public String quantityUnit() { return unit; }
        public CustomerOrderSummary(String id, String orderCode, String shopName, String serviceName,
                BigDecimal kilos, BigDecimal totalPrice, LaundryStage stage, String paymentStatus,
                String paymentMethod, Instant paidAt, Instant createdAt, String unit) {
            this(id, orderCode, shopName, serviceName, kilos, totalPrice, stage, paymentStatus,
                    paymentMethod, paidAt, createdAt, unit, OrderPricing.billingType(null, unit), null);
        }
    }
    public record CustomerStageEvent(LaundryStage stage, Instant changedAt) {}
    public record CustomerPaymentEvent(String status, String method, Instant occurredAt) {}
    public record CustomerOrdersPage(List<CustomerOrderSummary> content, int number, int size, long totalElements, int totalPages) {}
    public record CustomerOrderDetail(CustomerOrderSummary order, List<CustomerStageEvent> history,
            List<CustomerPaymentEvent> paymentHistory, Invoice invoice) {
        public CustomerOrderDetail(CustomerOrderSummary order, List<CustomerStageEvent> history,
                List<CustomerPaymentEvent> paymentHistory) {
            this(order, history, paymentHistory, null);
        }
    }

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
        DocumentSnapshot shop = shopId == null || shopId.isBlank() ? null
                : FirestoreSupport.await(db.collection(SHOPS).document(shopId).get());
        QuerySnapshot historyRows = FirestoreSupport.await(db.collection(HISTORY)
                .whereEqualTo("shopId", shopId).whereEqualTo("orderId", id).get());
        List<CustomerStageEvent> history = new ArrayList<>();
        for (DocumentSnapshot row : historyRows.getDocuments())
            history.add(new CustomerStageEvent(parseStage(row.getString("stage")), instant(row.get("changedAt"))));
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
        boolean isPaidOrder = "PAID".equals(order.getString("paymentStatus"));
        Invoice customerInvoice = isPaidOrder ? invoice(order, shop) : null;
        return new CustomerOrderDetail(customerSummary(db, order), List.copyOf(history), List.copyOf(payments), customerInvoice);
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
        DocumentSnapshot shopSnapshot = FirestoreSupport.await(db.collection(SHOPS).document(shopId).get());
        requireActiveShop(shopSnapshot);
        return new OrderDetail(mapOrder(snapshot), List.copyOf(history), List.copyOf(prices), invoice(snapshot, shopSnapshot));
    }

    public OrderSummary create(FirebasePrincipal principal, CreateOrderRequest request) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order details are required.");
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
        BigDecimal quantity = validateKilos(request.quantity());
        BigDecimal actualWeightKg = validateActualWeight(request.actualWeightKg());
        List<AddOnSelection> requestedAddOns = request.addOns() == null ? List.of() : new ArrayList<>(request.addOns());
        validateAddOnSelections(requestedAddOns);
        final List<AddOnSelection> addOnSelections = List.copyOf(requestedAddOns);
        String paymentMethodPreference = validatePaymentMethodPreference(request.paymentMethodPreference());
        String paymentTiming = validatePaymentTiming(request.paymentTiming());
        String clientRequestId = validateClientRequestId(request.clientRequestId());
        String notes = request.notes() == null ? "" : request.notes().trim();
        String addOnFingerprint = addOnSelections.stream().map(line -> line.addOnId() + ":" + line.quantity()).reduce((a, b) -> a + "," + b).orElse("");
        String requestFingerprint = orderRequestFingerprint(requestedCustomerName, mobile, serviceId, quantity.toPlainString(),
                actualWeightKg == null ? "" : actualWeightKg.toPlainString(), addOnFingerprint, notes, normalizedCustomerEmail,
                paymentMethodPreference, paymentTiming);

        if (mobile.length() < 7 || mobile.length() > 15) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid mobile number with 7 to 15 digits.");

        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference counter = db.collection(COUNTERS).document(shopId);
        DocumentReference service = db.collection(SERVICES).document(serviceId);
        DocumentReference customer = resolvedCustomer == null ? null : db.collection("users").document(resolvedCustomer.id());
        var requestCollection = shop.collection("orderCreateRequests");
        DocumentReference requestKey = requestCollection == null ? null : requestCollection.document(clientRequestId);
        DocumentReference order = db.collection(ORDERS).document();
        DocumentReference stageHistory = db.collection(HISTORY).document();
        DocumentReference priceHistory = order.collection(PRICE_LOG).document();
        String changedBy = userId(principal);
        String changedByUid = principal.uid();
        String changedByName = displayName(principal);
        Instant createdAt = Instant.now();

        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            requireActiveShop(shopSnapshot);
            DocumentSnapshot requestSnapshot = requestKey == null ? null : FirestoreSupport.await(tx.get(requestKey));
            if (requestSnapshot != null && requestSnapshot.exists()) {
                if (!requestFingerprint.equals(requestSnapshot.getString("requestFingerprint")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This client request UUID was already used for different order details.");
                String existingOrderId = requestSnapshot.getString("orderId");
                if (existingOrderId == null || existingOrderId.isBlank())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The prior order request needs reconciliation.");
                DocumentSnapshot existingOrder = FirestoreSupport.await(tx.get(db.collection(ORDERS).document(existingOrderId)));
                requireOwned(existingOrder, shopId);
                return mapOrder(existingOrder);
            }
            DocumentSnapshot counterSnapshot = FirestoreSupport.await(tx.get(counter));
            DocumentSnapshot serviceSnapshot = FirestoreSupport.await(tx.get(service));
            DocumentSnapshot customerSnapshot = customer == null ? null : FirestoreSupport.await(tx.get(customer));
            requireActiveService(serviceSnapshot, shopId);
            if ((serviceSnapshot.getString("billingType") == null || serviceSnapshot.getString("billingType").isBlank())
                    && (serviceSnapshot.getString("unit") == null || serviceSnapshot.getString("unit").isBlank()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected service has no billing unit. Update the service before creating an order.");
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
            String billingType = OrderPricing.billingType(serviceSnapshot.getString("billingType"), serviceSnapshot.getString("unit"));
            if (!List.of("PER_KG", "PER_LOAD", "PER_PIECE", "FLAT").contains(billingType))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected service has an unsupported billing type.");
            OrderPricing.validateQuantity(billingType, quantity);
            if (actualWeightKg != null && !"PER_LOAD".equals(billingType))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actual weight is only available for per-load services.");
            BigDecimal serviceUnitPrice = requireCatalogPrice(serviceSnapshot.get("pricePerUnit"), "Service");
            BigDecimal capacityKg = OrderPricing.decimal(serviceSnapshot.get("capacityKg"));
            BigDecimal minimumKg = OrderPricing.decimal(serviceSnapshot.get("minimumKg"));
            if (capacityKg != null && (capacityKg.compareTo(BigDecimal.ZERO) <= 0 || capacityKg.compareTo(MAX_KILOS) > 0 || capacityKg.scale() > 2))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected service has an invalid load capacity.");
            if (minimumKg != null && (minimumKg.compareTo(BigDecimal.ZERO) <= 0 || minimumKg.compareTo(MAX_KILOS) > 0 || minimumKg.scale() > 2))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected service has an invalid minimum weight.");
            if (!"PER_KG".equals(billingType)) minimumKg = null;
            if (actualWeightKg != null && capacityKg != null
                    && actualWeightKg.compareTo(capacityKg.multiply(quantity)) > 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actual weight exceeds the selected service's combined load capacity.");
            String unit = OrderPricing.unit(billingType);
            BigDecimal serviceSubtotal = OrderPricing.serviceSubtotal(billingType, serviceUnitPrice, quantity, minimumKg);
            List<DocumentSnapshot> addOnSnapshots = new ArrayList<>();
            Map<String, Long> stockDeductions = new HashMap<>();
            Map<String, DocumentReference> stockReferences = new HashMap<>();
            List<OrderAddOnLine> addOnLines = new ArrayList<>();
            List<Map<String, Object>> addOnValues = new ArrayList<>();
            BigDecimal addOnsSubtotal = BigDecimal.ZERO.setScale(2);
            for (AddOnSelection selected : addOnSelections) {
                DocumentReference addOnRef = db.collection("addOns").document(selected.addOnId());
                DocumentSnapshot addOnSnapshot = FirestoreSupport.await(tx.get(addOnRef));
                requireActiveAddOn(addOnSnapshot, shopId);
                ShopAddOnCatalog.AddOn addOn = ShopAddOnCatalog.map(addOnSnapshot);
                BigDecimal addOnPrice = OrderPricing.money(addOn.addOnPrice());
                BigDecimal lineTotal = OrderPricing.lineTotal(addOnPrice, BigDecimal.valueOf(selected.quantity()));
                addOnSnapshots.add(addOnSnapshot);
                addOnLines.add(new OrderAddOnLine(addOn.id(), addOn.itemName(), addOn.unit(), addOnPrice,
                        selected.quantity(), lineTotal));
                Map<String, Object> line = new HashMap<>();
                line.put("id", addOn.id()); line.put("name", addOn.itemName()); line.put("unit", addOn.unit());
                line.put("unitPrice", addOnPrice.doubleValue()); line.put("quantity", selected.quantity());
                line.put("lineTotal", lineTotal.doubleValue());
                addOnValues.add(line);
                addOnsSubtotal = addOnsSubtotal.add(lineTotal).setScale(2, RoundingMode.HALF_UP);
                if (addOn.trackStock()) {
                    if (addOn.stockOnHand() == null || addOn.stockOnHand() < 0)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock tracking is enabled but stock is unavailable for " + addOn.itemName() + ".");
                    long deduction = (long) addOn.stockDeductionPerUnit() * selected.quantity();
                    stockDeductions.merge(addOn.id(), deduction, Long::sum);
                    stockReferences.put(addOn.id(), addOnRef);
                }
            }
            Map<String, Integer> remainingStock = new HashMap<>();
            for (int i = 0; i < addOnSelections.size(); i++) {
                AddOnSelection selected = addOnSelections.get(i);
                ShopAddOnCatalog.AddOn addOn = ShopAddOnCatalog.map(addOnSnapshots.get(i));
                if (!addOn.trackStock() || remainingStock.containsKey(addOn.id())) continue;
                long deduction = stockDeductions.getOrDefault(addOn.id(), 0L);
                if (deduction > addOn.stockOnHand())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient stock for " + addOn.itemName() + ". Available: " + addOn.stockOnHand() + ".");
                remainingStock.put(addOn.id(), Math.toIntExact(addOn.stockOnHand() - deduction));
            }
            BigDecimal total = serviceSubtotal.add(addOnsSubtotal).setScale(2, RoundingMode.HALF_UP);
            if (total.compareTo(MAX_PRICE) > 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Computed order total exceeds PHP 999999.99.");
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
            Map<String, Object> serviceValues = new HashMap<>();
            serviceValues.put("id", serviceId); serviceValues.put("name", serviceName);
            serviceValues.put("category", first(serviceSnapshot.getString("category"), "Other"));
            serviceValues.put("billingType", billingType); serviceValues.put("unitPrice", serviceUnitPrice.doubleValue());
            serviceValues.put("capacityKg", capacityKg == null ? null : capacityKg.doubleValue());
            serviceValues.put("notes", first(serviceSnapshot.getString("notes"), ""));
            orderValues.put("service", serviceValues);
            orderValues.put("quantity", quantity.doubleValue());
            orderValues.put("quantityUnit", unit);
            orderValues.put("unit", unit);
            if ("PER_KG".equals(billingType)) orderValues.put("kilos", quantity.doubleValue());
            if (actualWeightKg != null) orderValues.put("actualWeightKg", actualWeightKg.doubleValue());
            orderValues.put("addOns", List.copyOf(addOnValues));
            orderValues.put("serviceSubtotal", serviceSubtotal.doubleValue());
            orderValues.put("addOnsSubtotal", addOnsSubtotal.doubleValue());
            orderValues.put("total", total.doubleValue());
            orderValues.put("paymentMethodPreference", paymentMethodPreference);
            orderValues.put("paymentTiming", paymentTiming);
            orderValues.put("totalPriceCentavos", Money.toCentavos(total));
            orderValues.put("notes", notes);
            orderValues.put("stage", LaundryStage.RECEIVED.name());
            orderValues.put("paymentStatus", "UNPAID");
            orderValues.put("paymentMethod", null);
            orderValues.put("paidAt", null);
            orderValues.put("paidByUid", null);
            orderValues.put("paidByName", null);
            orderValues.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            orderValues.put("createdBy", changedBy);
            Map<String, Object> priceValues = priceHistoryValues(null, total, changedByUid, changedByName);
            for (Map.Entry<String, Integer> stock : remainingStock.entrySet())
                tx.update(stockReferences.get(stock.getKey()), Map.of("stockOnHand", stock.getValue(),
                        "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            tx.set(counter, Map.of("shopId", shopId, "lastNumber", nextNumber));
            tx.create(order, orderValues);
            if (requestKey != null) tx.create(requestKey, Map.of("shopId", shopId, "requestFingerprint", requestFingerprint, "orderId", order.getId(), "createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            tx.create(stageHistory, historyValues(shopId, order.getId(), LaundryStage.RECEIVED, changedBy, changedByName));
            tx.create(priceHistory, priceValues);
            return new OrderSummary(order.getId(), shopId, code, customerName, mobile, resolvedCustomer == null ? null : resolvedCustomer.id(), serviceId, serviceName,
                    quantity, total, notes, LaundryStage.RECEIVED, createdAt, changedBy, "UNPAID", null, null, null, null,
                    null, null, null, null, unit, paymentMethodPreference, paymentTiming, billingType, actualWeightKg);
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
            BigDecimal previousPrice = orderPrice(current);
            tx.update(order, Map.of("totalPriceCentavos", Money.toCentavos(newPrice),
                    "totalPrice", com.google.cloud.firestore.FieldValue.delete(),
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
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
        DocumentReference paymentRef = db.collection(PAYMENTS).document("cash-" + id);
        String paidByUid = principal.uid();
        String paidByName = displayName(principal);
        Instant paidAt = Instant.now();
        DocumentSnapshot before = FirestoreSupport.await(order.get());
        requireOwned(before, shopId);
        requireActiveShop(FirestoreSupport.await(shop.get()));
        if (isPaid(before)) {
            if ("CASH".equals(before.getString("paymentMethod"))) return mapOrder(before);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already paid online.");
        }
        if (paymentMethodPreference(before) != null && !"CASH".equals(paymentMethodPreference(before)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is configured for online payment.");
        requirePaymentTiming(before);
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
            DocumentSnapshot existingCashPayment = FirestoreSupport.await(tx.get(paymentRef));
            requireActiveShop(shopSnapshot);
            requireOwned(current, shopId);
            if (isPaid(current)) {
                if ("CASH".equals(current.getString("paymentMethod"))) return mapOrder(current);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already paid.");
            }
            if (paymentMethodPreference(current) != null && !"CASH".equals(paymentMethodPreference(current)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is configured for online payment.");
            requirePaymentTiming(current);
            if (existingCashPayment.exists()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A cash payment record already exists for this unpaid order; reconcile it before retrying.");
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
            tx.create(paymentRef, paymentRecord(shopId, id, orderPrice(current), "CASH", "PAID", paidByUid, paidByName, null));
            return withPayment(mapOrder(current), "PAID", "CASH", paidAt, paidByUid, paidByName);
        });
        return result;
    }

    public PaymentLinkResult createPaymentLink(FirebasePrincipal principal, String orderId, PaymentLinkRequest request) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String id = requireId(orderId);
        String clientRequestId = validateClientRequestId(request == null ? null : request.clientRequestId());
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document(id);
        var requestCollection = order.collection("paymentLinkRequests");
        DocumentReference requestKey = requestCollection == null ? null : requestCollection.document(clientRequestId);
        PaymentLinkResult replay = FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            DocumentSnapshot requestSnapshot = requestKey == null ? null : FirestoreSupport.await(tx.get(requestKey));
            requireActiveShop(shopSnapshot);
            requireOwned(current, shopId);
            if (requestSnapshot != null && requestSnapshot.exists()) {
                if ("COMPLETED".equals(requestSnapshot.getString("status")))
                    return new PaymentLinkResult(mapOrder(current), requestSnapshot.getString("paymentLinkId"), requestSnapshot.getString("paymentLinkUrl"));
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment-link request is already being processed. Refresh before retrying.");
            }
            if ("PENDING".equals(paymentStatus(current))) {
                String existingId = current.getString("paymentLinkId");
                String existingUrl = current.getString("paymentLinkUrl");
                if (existingId != null && existingUrl != null) return new PaymentLinkResult(mapOrder(current), existingId, existingUrl);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The pending payment link needs reconciliation.");
            }
            if (!"UNPAID".equals(paymentStatus(current)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A payment link can be created only for an unpaid order.");
            if (paymentMethodPreference(current) != null && !"ONLINE".equals(paymentMethodPreference(current)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is configured for cash payment.");
            requirePaymentTiming(current);
            long cents = Money.toCentavos(orderPrice(current));
            if (cents < 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PayMongo payment links require a total of at least ₱1.00.");
            if (requestKey != null) tx.create(requestKey, Map.of("shopId", shopId, "orderId", id, "status", "CREATING",
                    "amountCentavos", cents, "createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return null;
        });
        if (replay != null) return replay;
        DocumentSnapshot initialOrder = FirestoreSupport.await(order.get());
        BigDecimal total = orderPrice(initialOrder);
        long amountCentavos = Money.toCentavos(total);
        PayMongoGateway.CreatedLink link = gateway().createPaymentLink(amountCentavos, id, initialOrder.getString("orderCode"));
        if (link.amountCentavos() != amountCentavos || !"PHP".equals(link.currency())) {
            try { gateway().archivePaymentLink(link.id()); } catch (RuntimeException ignored) { }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayMongo returned a link with an unexpected amount or currency.");
        }
        DocumentReference payment = db.collection(PAYMENTS).document("online-" + clientRequestId);
        String paymentRecordId = payment.getId();
        try {
            OrderSummary updated = FirestoreSupport.transact(db, tx -> {
                DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
                DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
                DocumentSnapshot requestSnapshot = requestKey == null ? null : FirestoreSupport.await(tx.get(requestKey));
                requireActiveShop(shopSnapshot);
                requireOwned(current, shopId);
                if (requestSnapshot != null && requestSnapshot.exists() && "COMPLETED".equals(requestSnapshot.getString("status")))
                    return mapOrder(current);
                if (!"ONLINE".equals(paymentMethodPreference(current)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is no longer configured for online payment.");
                requirePaymentTiming(current);
                if (!"UNPAID".equals(paymentStatus(current)) || orderPrice(current).compareTo(total) != 0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The order changed before its payment link was created. Refresh and try again.");
                Map<String, Object> values = new HashMap<>();
                values.put("paymentStatus", "PENDING"); values.put("paymentMethod", "ONLINE");
                values.put("paymentLinkId", link.id()); values.put("paymentLinkUrl", link.url());
                values.put("paymentAmountCentavos", amountCentavos); values.put("paymentOrderIdReference", id);
                values.put("paymentRecordId", paymentRecordId);
                values.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                tx.update(order, values);
                if (requestKey != null) tx.update(requestKey, Map.of("status", "COMPLETED", "paymentLinkId", link.id(), "paymentLinkUrl", link.url(),
                        "completedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
                tx.create(payment, paymentRecord(shopId, id, total, "ONLINE", "PENDING", principal.uid(), displayName(principal), link.id()));
                return withLink(mapOrder(current), link.id(), link.url(), amountCentavos, id);
            });
            return new PaymentLinkResult(updated, link.id(), link.url());
        } catch (RuntimeException exception) {
            try { gateway().archivePaymentLink(link.id()); } catch (RuntimeException ignored) { }
            throw exception;
        }
    }

    /** Compatibility entry point for older in-process callers; HTTP callers must provide a request UUID. */
    public PaymentLinkResult createPaymentLink(FirebasePrincipal principal, String orderId) {
        return createPaymentLink(principal, orderId, new PaymentLinkRequest(UUID.randomUUID().toString()));
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
        if (isPaidOnlineForStoredLink(before)) return mapOrder(before);
        if (!"PENDING".equals(paymentStatus(before))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has no pending online payment.");
        String linkId = before.getString("paymentLinkId");
        PayMongoGateway.VerifiedPayment verified = gateway().findPaidPayment(linkId);
        if (!matchesStoredPayment(before, id, verified))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PayMongo has not confirmed a payment matching this order and amount.");
        String paymentRecordId = before.getString("paymentRecordId");
        Instant paidAt = Instant.now();
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            requireActiveShop(shopSnapshot); requireOwned(current, shopId);
            if (isPaidOnlineForStoredLink(current) && java.util.Objects.equals(linkId, current.getString("paymentLinkId")))
                return mapOrder(current);
            if (!"PENDING".equals(paymentStatus(current)) || !java.util.Objects.equals(linkId, current.getString("paymentLinkId"))
                    || !matchesStoredPayment(current, id, verified))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The pending payment changed before confirmation. Refresh the order.");
            Map<String, Object> update = new HashMap<>();
            update.put("paymentStatus", "PAID"); update.put("paymentMethod", "ONLINE");
            update.put("paidAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            update.put("paidByUid", "PAYMONGO"); update.put("paidByName", "PayMongo");
            update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.update(order, update);
            if (paymentRecordId != null) tx.update(db.collection(PAYMENTS).document(paymentRecordId), Map.of(
                    "status", "PAID", "externalPaymentId", verified.id(), "paidAt", com.google.cloud.firestore.FieldValue.serverTimestamp(),
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return withPayment(mapOrder(current), "PAID", "ONLINE", paidAt, "PAYMONGO", "PayMongo");
        });
    }

    /** Provider-confirmed path used only after a signed PayMongo webhook has been durably recorded. */
    public void confirmOnlinePaymentByLink(String linkId) {
        if (linkId == null || !linkId.matches("[A-Za-z0-9_-]{5,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid PayMongo payment link reference.");
        Firestore db = firebase.firestore();
        QuerySnapshot orderRows = FirestoreSupport.await(db.collection(ORDERS).whereEqualTo("paymentLinkId", linkId).limit(2).get());
        DocumentSnapshot before = orderRows.getDocuments().isEmpty() ? null : orderRows.getDocuments().get(0);
        DocumentSnapshot linkedPayment = null;
        if (before == null) {
            QuerySnapshot paymentRows = FirestoreSupport.await(db.collection(PAYMENTS).whereEqualTo("paymentLinkId", linkId).limit(2).get());
            if (paymentRows.getDocuments().isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment link is not associated with an order.");
            linkedPayment = paymentRows.getDocuments().get(0);
            String linkedOrderId = linkedPayment.getString("orderId");
            if (linkedOrderId == null || linkedOrderId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment link is not associated with an order.");
            before = FirestoreSupport.await(db.collection(ORDERS).document(linkedOrderId).get());
        }
        if (!before.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment order was not found.");
        String id = before.getId();
        String shopId = before.getString("shopId");
        if (shopId == null || shopId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment order was not found.");
        if (isPaidOnlineForStoredLink(before) && linkId.equals(before.getString("paymentLinkId"))) return;

        String paymentRecordId = before.getString("paymentRecordId");
        DocumentReference paymentRef = paymentRecordId != null ? db.collection(PAYMENTS).document(paymentRecordId)
                : linkedPayment != null ? db.collection(PAYMENTS).document(linkedPayment.getId()) : null;
        DocumentSnapshot payment = linkedPayment != null ? linkedPayment
                : paymentRef == null ? null : FirestoreSupport.await(paymentRef.get());
        PayMongoGateway.VerifiedPayment verified = gateway().findPaidPayment(linkId);
        if (!matchesStoredPayment(before, id, verified) && !matchesPaymentRecord(payment, id, linkId, verified))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PayMongo has not confirmed a payment matching the stored order and amount.");

        DocumentReference orderRef = db.collection(ORDERS).document(id);
        FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(orderRef));
            if (!current.exists() || !shopId.equals(current.getString("shopId")))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment order was not found.");
            if (isPaidOnlineForStoredLink(current) && linkId.equals(current.getString("paymentLinkId"))) return null;
            if ("PENDING".equals(paymentStatus(current)) && linkId.equals(current.getString("paymentLinkId"))
                    && matchesStoredPayment(current, id, verified)) {
                Map<String, Object> update = new HashMap<>();
                update.put("paymentStatus", "PAID"); update.put("paymentMethod", "ONLINE");
                update.put("paidAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                update.put("paidByUid", "PAYMONGO"); update.put("paidByName", "PayMongo");
                update.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                tx.update(orderRef, update);
                if (paymentRef != null) tx.update(paymentRef, Map.of("status", "PAID", "externalPaymentId", verified.id(),
                        "paidAt", com.google.cloud.firestore.FieldValue.serverTimestamp(),
                        "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
                return null;
            }
            if (paymentRef != null && matchesPaymentRecord(payment, id, linkId, verified)) {
                tx.update(paymentRef, Map.of("status", "RECONCILIATION_REQUIRED", "externalPaymentId", verified.id(),
                        "reconciliationReason", "PROVIDER_PAYMENT_AFTER_CASH_OR_CANCELLATION",
                        "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
                tx.update(orderRef, Map.of("paymentReconciliationRequired", true,
                        "paymentReconciliationUpdatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
                return null;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The stored payment changed before provider confirmation.");
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
        PayMongoGateway.VerifiedPayment afterArchive = gateway().findPaidPayment(linkId);
        if (matchesStoredPayment(FirestoreSupport.await(order.get()), id, afterArchive)) {
            confirmOnlinePayment(principal, id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment completed while the link was being cancelled.");
        }
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

    public OrderSummary advance(FirebasePrincipal principal, String orderId, ExpectedStageRequest request) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        String id = requireId(orderId);
        LaundryStage expected = parseStage(request == null ? null : request.expectedStage());
        if (expected == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The expected stage is invalid.");
        String clientRequestId = validateClientRequestId(request.clientRequestId());
        Firestore db = firebase.firestore();
        DocumentReference shop = db.collection(SHOPS).document(shopId);
        DocumentReference order = db.collection(ORDERS).document(id);
        var requestCollection = order.collection("stageAdvanceRequests");
        DocumentReference requestKey = requestCollection == null ? null : requestCollection.document(clientRequestId);
        DocumentReference history = db.collection(HISTORY).document();
        String changedBy = userId(principal);
        String changedByName = displayName(principal);
        String notificationCorrelationId = UUID.randomUUID().toString();
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot shopSnapshot = FirestoreSupport.await(tx.get(shop));
            DocumentSnapshot current = FirestoreSupport.await(tx.get(order));
            DocumentSnapshot requestSnapshot = requestKey == null ? null : FirestoreSupport.await(tx.get(requestKey));
            requireActiveShop(shopSnapshot);
            requireOwned(current, shopId);
            if (requestSnapshot != null && requestSnapshot.exists()) {
                if (!expected.name().equals(requestSnapshot.getString("expectedStage")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This client request UUID was already used for a different stage transition.");
                LaundryStage storedResult = parseStage(requestSnapshot.getString("resultStage"));
                if (storedResult == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "The prior stage request needs reconciliation.");
                return replaceStage(mapOrder(current), storedResult);
            }
            LaundryStage stage = parseStage(current.getString("stage"));
            if (stage == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "The order has an invalid stage and cannot be advanced.");
            if (stage != expected) throw new ResponseStatusException(HttpStatus.CONFLICT, "The order stage changed. Refresh before advancing it.");
            if (stage == LaundryStage.CLAIMED) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already at the final stage, Claimed.");
            LaundryStage next = stage.next();
            if (next == LaundryStage.CLAIMED && !isPaid(current)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order must be paid before it can be claimed");
            }
            Map<String, Object> stageUpdate = new HashMap<>();
            stageUpdate.put("stage", next.name());
            stageUpdate.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            if (next == LaundryStage.READY) stageUpdate.put("readyAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            tx.update(order, stageUpdate);
            tx.create(history, historyValues(shopId, id, next, changedBy, changedByName));
            if (requestKey != null) tx.create(requestKey, Map.of("shopId", shopId, "orderId", id, "expectedStage", expected.name(),
                    "resultStage", next.name(), "createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            if (next == LaundryStage.READY && current.getString("customerUserId") != null
                    && !current.getString("customerUserId").isBlank()) {
                DocumentReference notification = db.collection("notificationOutbox").document(id + "_READY");
                Map<String, Object> event = new HashMap<>();
                event.put("eventId", id + "_READY"); event.put("eventType", "READY");
                event.put("correlationId", notificationCorrelationId);
                event.put("shopId", shopId); event.put("orderId", id);
                event.put("customerUserId", current.getString("customerUserId"));
                event.put("status", "PENDING"); event.put("attempts", 0);
                event.put("nextAttemptAtMillis", System.currentTimeMillis());
                event.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                tx.create(notification, event);
            }
            return replaceStage(mapOrder(current), next);
        });
    }

    /** Compatibility entry point for older in-process callers; HTTP callers must provide expectedStage. */
    public OrderSummary advance(FirebasePrincipal principal, String orderId) {
        requireRole(principal, "STAFF");
        String shopId = requireShop(principal.shopId());
        DocumentSnapshot current = FirestoreSupport.await(firebase.firestore().collection(ORDERS).document(requireId(orderId)).get());
        requireOwned(current, shopId);
        LaundryStage currentStage = parseStage(current.getString("stage"));
        if (currentStage == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "The order has an invalid stage and cannot be advanced.");
        return advance(principal, orderId, new ExpectedStageRequest(currentStage.name(), UUID.randomUUID().toString()));
    }

    private static void requireActiveService(DocumentSnapshot service, String shopId) {
        if (!service.exists() || !shopId.equals(service.getString("shopId"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected service was not found in this shop.");
        if (!Boolean.TRUE.equals(service.getBoolean("active"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected service is no longer active. Refresh the service list and try again.");
    }

    private static void validateAddOnSelections(List<AddOnSelection> selections) {
        if (selections == null || selections.size() > 20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An order can contain at most 20 add-on rows.");
        for (AddOnSelection selection : selections) {
            if (selection == null || selection.addOnId() == null || selection.addOnId().isBlank()
                    || selection.addOnId().contains("/") || selection.quantity() == null
                    || selection.quantity() < 1 || selection.quantity() > 999)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each add-on needs a valid ID and quantity from 1 to 999.");
        }
    }

    private static void requireActiveAddOn(DocumentSnapshot addOn, String shopId) {
        if (!addOn.exists() || !shopId.equals(addOn.getString("shopId")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected add-on was not found in this shop.");
        if (!Boolean.TRUE.equals(addOn.getBoolean("active")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected add-on is no longer active. Refresh the add-on list and try again.");
    }

    private static BigDecimal requireCatalogPrice(Object raw, String label) {
        BigDecimal price = OrderPricing.decimal(raw);
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0 || price.compareTo(MAX_PRICE) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected " + label.toLowerCase(Locale.ROOT) + " has an invalid catalog price.");
        return OrderPricing.money(price);
    }

    private static BigDecimal validateActualWeight(BigDecimal value) {
        if (value == null) return null;
        if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(MAX_KILOS) > 0 || value.scale() > 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actual weight must be greater than 0 and no more than 999.99 kg, with at most 2 decimal places.");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static Invoice invoice(DocumentSnapshot order, DocumentSnapshot shop) {
        Map<?, ?> service = order.get("service") instanceof Map<?, ?> values ? values : Map.of();
        String billingType = first(string(service.get("billingType")), OrderPricing.billingType(null, orderUnit(order)));
        BigDecimal quantity = orderQuantity(order);
        BigDecimal addOnsSubtotal = nullableDecimal(order.get("addOnsSubtotal"));
        if (addOnsSubtotal == null) addOnsSubtotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        Object rawAddOns = order.get("addOns");
        List<OrderAddOnLine> addOns = new ArrayList<>();
        if (rawAddOns instanceof List<?> values) for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> line)) continue;
            String id = string(line.get("id"));
            String name = string(line.get("name"));
            String unit = string(line.get("unit"));
            BigDecimal unitPrice = nullableDecimal(line.get("unitPrice"));
            BigDecimal lineTotal = nullableDecimal(line.get("lineTotal"));
            Object lineQuantity = line.get("quantity");
            int count = lineQuantity instanceof Number number ? number.intValue() : 0;
            addOns.add(new OrderAddOnLine(id, name, unit, unitPrice, count, lineTotal));
        }
        BigDecimal total = orderPrice(order);
        BigDecimal serviceSubtotal = nullableDecimal(order.get("serviceSubtotal"));
        if (serviceSubtotal == null) serviceSubtotal = total.subtract(addOnsSubtotal).setScale(2, RoundingMode.HALF_UP);
        ServiceSnapshot serviceSnapshot = new ServiceSnapshot(
                first(string(service.get("id")), order.getString("serviceId")),
                first(string(service.get("name")), order.getString("serviceName")),
                first(string(service.get("category")), "Other"), billingType,
                nullableDecimal(service.get("unitPrice")), nullableDecimal(service.get("capacityKg")),
                first(string(service.get("notes")), ""));
        String paymentMethod = order.getString("paymentMethod");
        if (paymentMethod == null) paymentMethod = paymentMethodPreference(order);
        return new Invoice(first(shop == null ? null : shop.getString("name"), "Laundry shop"), first(shop == null ? null : shop.getString("contactNumber"), ""),
                order.getString("orderCode"), instant(order.get("createdAt")), order.getString("customerName"),
                serviceSnapshot, quantity, first(order.getString("quantityUnit"), orderUnit(order)),
                nullableDecimal(order.get("actualWeightKg")), List.copyOf(addOns), serviceSubtotal, addOnsSubtotal,
                total, paymentMethod, paymentTiming(order), paymentStatus(order), instant(order.get("paidAt")));
    }

    private static String string(Object value) { return value instanceof String text ? text : null; }
    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback == null ? "" : fallback : value;
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
        values.put("previousPrice", previousPrice == null ? null : Money.toCentavos(previousPrice));
        values.put("newPrice", Money.toCentavos(newPrice)); values.put("priceUnit", "CENTAVOS");
        values.put("changedByUid", uid); values.put("changedByName", name);
        values.put("changedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        return values;
    }

    private static Map<String, Object> paymentRecord(String shopId, String orderId, BigDecimal amount, String method,
            String status, String actorUid, String actorName, String linkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("shopId", shopId); values.put("orderId", orderId); values.put("amountCentavos", Money.toCentavos(amount));
        values.put("method", method); values.put("status", status); values.put("createdByUid", actorUid);
        values.put("createdByName", actorName); values.put("paymentLinkId", linkId);
        values.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        values.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
        if ("PAID".equals(status)) values.put("paidAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
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
                order.getString("serviceName"), orderQuantity(order), orderPrice(order),
                parseStage(order.getString("stage")), paymentStatus(order), order.getString("paymentMethod"),
                instant(order.get("paidAt")), instant(order.get("createdAt")), orderUnit(order),
                orderBillingType(order), nullableDecimal(order.get("actualWeightKg")));
    }

    private static OrderSummary mapOrder(DocumentSnapshot snapshot) {
        String name = snapshot.getString("customerName");
        return new OrderSummary(snapshot.getId(), snapshot.getString("shopId"), snapshot.getString("orderCode"),
                name == null ? "" : name, snapshot.getString("mobile"), snapshot.getString("customerUserId"),
                snapshot.getString("serviceId"), snapshot.getString("serviceName"), orderQuantity(snapshot),
                orderPrice(snapshot), snapshot.getString("notes"), parseStage(snapshot.getString("stage")),
                instant(snapshot.get("createdAt")), snapshot.getString("createdBy"), paymentStatus(snapshot),
                snapshot.getString("paymentMethod"), instant(snapshot.get("paidAt")), snapshot.getString("paidByUid"),
                snapshot.getString("paidByName"), snapshot.getString("paymentLinkId"), snapshot.getString("paymentLinkUrl"),
                snapshot.getLong("paymentAmountCentavos"), snapshot.getString("paymentOrderIdReference"), orderUnit(snapshot),
                paymentMethodPreference(snapshot), paymentTiming(snapshot),
                orderBillingType(snapshot), nullableDecimal(snapshot.get("actualWeightKg")));
    }

    private static StageHistory mapHistory(DocumentSnapshot snapshot) {
        return new StageHistory(snapshot.getId(), parseStage(snapshot.getString("stage")), snapshot.getString("changedBy"),
                snapshot.getString("changedByName"), instant(snapshot.get("changedAt")));
    }
    private static PriceHistory mapPriceHistory(DocumentSnapshot snapshot) {
        String unit = snapshot.getString("priceUnit");
        return new PriceHistory(snapshot.getId(), Money.fromPriceLog(unit, snapshot.get("previousPrice")),
                Money.fromPriceLog(unit, snapshot.get("newPrice")),
                snapshot.getString("changedByUid"), snapshot.getString("changedByName"), instant(snapshot.get("changedAt")));
    }
    private static OrderSummary replaceStage(OrderSummary order, LaundryStage stage) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), order.totalPrice(), order.notes(), stage, order.createdAt(), order.createdBy(),
                order.paymentStatus(), order.paymentMethod(), order.paidAt(), order.paidByUid(), order.paidByName(), order.paymentLinkId(),
                order.paymentLinkUrl(), order.paymentAmountCentavos(), order.paymentOrderIdReference(), order.unit(),
                order.paymentMethodPreference(), order.paymentTiming(), order.billingType(), order.actualWeightKg());
    }
    private static OrderSummary replacePrice(OrderSummary order, BigDecimal price) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), price, order.notes(), order.stage(), order.createdAt(), order.createdBy(),
                order.paymentStatus(), order.paymentMethod(), order.paidAt(), order.paidByUid(), order.paidByName(), order.paymentLinkId(),
                order.paymentLinkUrl(), order.paymentAmountCentavos(), order.paymentOrderIdReference(), order.unit(),
                order.paymentMethodPreference(), order.paymentTiming(), order.billingType(), order.actualWeightKg());
    }
    private static OrderSummary withPayment(OrderSummary order, String status, String method, Instant paidAt, String uid, String name) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), order.totalPrice(), order.notes(), order.stage(), order.createdAt(),
                order.createdBy(), status, method, paidAt, uid, name, order.paymentLinkId(), order.paymentLinkUrl(),
                order.paymentAmountCentavos(), order.paymentOrderIdReference(), order.unit(), order.paymentMethodPreference(), order.paymentTiming(),
                order.billingType(), order.actualWeightKg());
    }
    private static OrderSummary withLink(OrderSummary order, String linkId, String url, long amount, String orderReference) {
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), order.totalPrice(), order.notes(), order.stage(), order.createdAt(),
                order.createdBy(), "PENDING", "ONLINE", order.paidAt(), order.paidByUid(), order.paidByName(), linkId, url, amount,
                orderReference, order.unit(), order.paymentMethodPreference(), order.paymentTiming(), order.billingType(), order.actualWeightKg());
    }
    private static OrderSummary mapOrderAfterPaymentReset(DocumentSnapshot current) {
        OrderSummary order = mapOrder(current);
        return new OrderSummary(order.id(), order.shopId(), order.orderCode(), order.customerName(), order.mobile(), order.customerUserId(),
                order.serviceId(), order.serviceName(), order.kilos(), order.totalPrice(), order.notes(), order.stage(), order.createdAt(),
                order.createdBy(), "UNPAID", null, null, null, null, null, null, null, null, order.unit(),
                order.paymentMethodPreference(), order.paymentTiming(), order.billingType(), order.actualWeightKg());
    }
    private static boolean matchesStoredPayment(DocumentSnapshot order, String orderId, PayMongoGateway.VerifiedPayment payment) {
        if (payment == null || !"PENDING".equals(paymentStatus(order))) return false;
        Long amount = order.getLong("paymentAmountCentavos");
        String reference = order.getString("paymentOrderIdReference");
        long orderTotal = Money.toCentavos(orderPrice(order));
        return ("paid".equalsIgnoreCase(payment.status()) || "succeeded".equalsIgnoreCase(payment.status()))
                && amount != null && amount.longValue() == payment.amountCentavos() && amount.longValue() == orderTotal
                && orderId.equals(reference) && orderId.equals(payment.orderIdReference());
    }
    private static boolean matchesPaymentRecord(DocumentSnapshot record, String orderId, String linkId,
            PayMongoGateway.VerifiedPayment payment) {
        if (record == null || !record.exists() || payment == null || !linkId.equals(record.getString("paymentLinkId"))) return false;
        Long amount = record.getLong("amountCentavos");
        if (amount == null) amount = Money.toCentavos(Money.fromFirestore(null, record.get("amount")));
        return orderId.equals(record.getString("orderId")) && amount.longValue() == payment.amountCentavos()
                && orderId.equals(payment.orderIdReference())
                && ("paid".equalsIgnoreCase(payment.status()) || "succeeded".equalsIgnoreCase(payment.status()));
    }
    private PayMongoGateway gateway() {
        if (payMongo == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "PayMongo test mode is not available in this service instance.");
        return payMongo;
    }
    private static boolean isPaid(DocumentSnapshot order) { return "PAID".equals(order.getString("paymentStatus")); }
    private static boolean isPaymentLocked(DocumentSnapshot order) { return "PAID".equals(paymentStatus(order)) || "PENDING".equals(paymentStatus(order)); }
    private static BigDecimal orderQuantity(DocumentSnapshot order) {
        Object quantityField = order.get("quantity");
        Object kilosField = order.get("kilos");
        Object raw = quantityField != null ? quantityField : kilosField;
        return raw == null ? null : nullableDecimal(raw);
    }
    private static String orderUnit(DocumentSnapshot order) {
        String unit = first(order.getString("quantityUnit"), order.getString("unit"));
        return unit.isBlank() ? "kg" : unit;
    }
    private static String orderBillingType(DocumentSnapshot order) {
        Map<?, ?> service = order.get("service") instanceof Map<?, ?> values ? values : Map.of();
        String configured = string(service.get("billingType"));
        if (configured != null && !configured.isBlank()) return OrderPricing.billingType(configured, null);
        String unit = first(order.getString("quantityUnit"), order.getString("unit"));
        if (!unit.isBlank()) return OrderPricing.billingType(null, unit);
        return order.get("kilos") != null || order.get("quantity") != null ? "PER_KG" : null;
    }
    private static String serviceUnit(DocumentSnapshot service) {
        String unit = service.getString("unit");
        if (unit == null || !List.of("kg", "piece", "load").contains(unit))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected service has an invalid unit. Update the service before creating an order.");
        return unit;
    }
    private static BigDecimal orderPrice(DocumentSnapshot order) {
        return Money.fromFirestore(order.get("totalPriceCentavos"), order.get("totalPrice"));
    }
    private static boolean isPaidOnlineForStoredLink(DocumentSnapshot order) {
        return "PAID".equals(paymentStatus(order)) && "ONLINE".equals(order.getString("paymentMethod"))
                && order.getString("paymentLinkId") != null && !order.getString("paymentLinkId").isBlank();
    }
    private static String paymentStatus(DocumentSnapshot order) {
        String status = order.getString("paymentStatus");
        return "PAID".equals(status) || "PENDING".equals(status) ? status : "UNPAID";
    }
    private static String paymentMethodPreference(DocumentSnapshot order) {
        String preference = order.getString("paymentMethodPreference");
        return "ONLINE".equals(preference) || "CASH".equals(preference) ? preference : null;
    }
    private static String paymentTiming(DocumentSnapshot order) {
        String timing = order.getString("paymentTiming");
        return timing == null || timing.isBlank() || "PAY_NOW".equals(timing) ? "PAY_NOW" : "PAY_AT_CLAIM";
    }
    private static void requirePaymentTiming(DocumentSnapshot order) {
        LaundryStage stage = parseStage(order.getString("stage"));
        if (stage == null || stage == LaundryStage.CLAIMED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment cannot be recorded at this order stage.");
        if ("PAY_AT_CLAIM".equals(paymentTiming(order)) && stage != LaundryStage.READY)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is set to be paid at claim. Record payment when it is Ready for pickup.");
    }
    private static String validatePaymentMethodPreference(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("CASH", "ONLINE").contains(normalized))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment method must be CASH or ONLINE.");
        return normalized;
    }
    private static String validatePaymentTiming(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PAY_NOW", "PAY_AT_CLAIM").contains(normalized))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment timing must be PAY_NOW or PAY_AT_CLAIM.");
        return normalized;
    }
    private static String validateClientRequestId(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid client request UUID is required.");
        return UUID.fromString(value).toString();
    }
    private static String orderRequestFingerprint(String... fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            String value = field == null ? "" : field;
            canonical.append(value.length()).append(':').append(value).append('|');
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
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
        BigDecimal parsed = nullableDecimal(value);
        return parsed == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : parsed;
    }
    private static BigDecimal nullableDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal.setScale(2, RoundingMode.HALF_UP);
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        if (value instanceof String text && !text.isBlank()) {
            try { return new BigDecimal(text.trim()).setScale(2, RoundingMode.HALF_UP); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
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
