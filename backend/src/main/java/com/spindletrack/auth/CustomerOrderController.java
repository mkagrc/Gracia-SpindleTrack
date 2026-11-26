package com.spindletrack.auth;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerOrderController {
    private final ShopOrderService orders;

    public CustomerOrderController(ShopOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public ShopOrderService.CustomerOrdersPage list(@AuthenticationPrincipal FirebasePrincipal principal,
            @RequestParam(defaultValue = "0") int page) {
        return orders.listMyOrders(principal, page);
    }

    @GetMapping("/{orderId}")
    public ShopOrderService.CustomerOrderDetail get(@PathVariable String orderId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.getMyOrder(principal, orderId);
    }

    @PostMapping("/{orderId}/payments/paypal-order")
    public ShopOrderService.PayPalOrderResult createPayPalOrder(@PathVariable String orderId,
            @RequestBody(required = false) ShopOrderService.PayPalOrderRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.createCustomerPayPalOrder(principal, orderId, request);
    }

    @PostMapping("/{orderId}/payments/paypal-capture")
    public ShopOrderService.OrderSummary capturePayPalOrder(@PathVariable String orderId,
            @RequestBody(required = false) ShopOrderService.PayPalCaptureRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return orders.captureCustomerPayPalOrder(principal, orderId, request);
    }
}
