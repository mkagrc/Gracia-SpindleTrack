package com.spindletrack.auth;

import java.math.BigDecimal;

public interface PayPalGateway {
    record CreatedOrder(String id, String approvalUrl, BigDecimal amount, String currency) {}
    record CapturedOrder(String id, String status, BigDecimal amount, String currency, String captureId) {}

    CreatedOrder createOrder(BigDecimal amount, String orderId, String orderCode);
    CapturedOrder captureOrder(String paypalOrderId);

    default String getOrderStatus(String paypalOrderId) {
        return "CREATED";
    }
}
