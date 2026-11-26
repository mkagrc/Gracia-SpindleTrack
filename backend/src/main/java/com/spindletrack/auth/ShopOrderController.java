package com.spindletrack.auth;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops/orders")
public class ShopOrderController {
    private final ShopOrderService orders;
    public ShopOrderController(ShopOrderService orders) { this.orders = orders; }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','STAFF')")
    public ShopOrderService.OrdersPage list(@AuthenticationPrincipal FirebasePrincipal principal,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page) {
        return orders.list(principal, stage, search, page);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('OWNER','STAFF')")
    public ShopOrderService.OrderDetail get(@PathVariable String orderId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.get(principal, orderId);
    }

    @GetMapping("/customer-match")
    @PreAuthorize("hasRole('STAFF')")
    public ShopOrderService.CustomerMatch findCustomer(@RequestParam String email,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.findCustomerForOrder(principal, email);
    }

    @PostMapping
    @PreAuthorize("hasRole('STAFF')")
    public ShopOrderService.OrderSummary create(@Valid @RequestBody ShopOrderService.CreateOrderRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.create(principal, request);
    }

    @PatchMapping("/{orderId}/price")
    @PreAuthorize("hasRole('STAFF')")
    public ShopOrderService.OrderSummary changePrice(@PathVariable String orderId,
            @Valid @RequestBody ShopOrderService.PriceChangeRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.changePrice(principal, orderId, request);
    }

    @PostMapping("/{orderId}/payments/cash")
    @PreAuthorize("hasRole('STAFF')")
    public ShopOrderService.OrderSummary recordCashPayment(@PathVariable String orderId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.recordCashPayment(principal, orderId);
    }

    @PostMapping("/{orderId}/payments/paypal-order")
    @PreAuthorize("hasRole('STAFF')")
    public ShopOrderService.PayPalOrderResult createPayPalOrder(@PathVariable String orderId,
            @RequestBody(required = false) ShopOrderService.PayPalOrderRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.createPayPalOrder(principal, orderId, request);
    }

    @PostMapping("/{orderId}/payments/paypal-capture")
    @PreAuthorize("hasRole('STAFF')")
    public ShopOrderService.OrderSummary capturePayPalOrder(@PathVariable String orderId,
            @RequestBody(required = false) ShopOrderService.PayPalCaptureRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.capturePayPalOrder(principal, orderId, request);
    }

    @PostMapping("/{orderId}/payments/cancel-link")
    @PreAuthorize("hasRole('STAFF')")
    public ShopOrderService.OrderSummary cancelPaymentLink(@PathVariable String orderId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.cancelPaymentLink(principal, orderId);
    }

    @PostMapping("/{orderId}/advance")
    @PreAuthorize("hasRole('STAFF')")
    public ShopOrderService.OrderSummary advance(@PathVariable String orderId,
            @Valid @RequestBody ShopOrderService.ExpectedStageRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.advance(principal, orderId, request);
    }
}
