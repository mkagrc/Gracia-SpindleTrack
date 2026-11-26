package com.spindletrack.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.spindletrack.LaundryStage;
import com.spindletrack.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerOrderController.class)
@Import(SecurityConfig.class)
class CustomerOrderControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShopOrderService orders;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository users;

    @Test
    void customerStageHistoryResponseNeverContainsStaffName() throws Exception {
        ShopOrderService.CustomerOrderSummary summary = new ShopOrderService.CustomerOrderSummary(
                "order-string-2", "O-000002", "Laundry One", "Wash", new java.math.BigDecimal("1.50"),
                new java.math.BigDecimal("30.00"), LaundryStage.WASHING, "UNPAID", null, null,
                java.time.Instant.now(), "kg");
        ShopOrderService.CustomerStageEvent event = new ShopOrderService.CustomerStageEvent(
                LaundryStage.RECEIVED, java.time.Instant.parse("2026-11-01T08:00:00Z"));
        when(orders.getMyOrder(any(), eq("order-string-2")))
                .thenReturn(new ShopOrderService.CustomerOrderDetail(summary, List.of(event), List.of()));

        mvc.perform(get("/api/me/orders/order-string-2").with(authentication(auth("CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].stage").value("RECEIVED"))
                .andExpect(jsonPath("$.history[0].changedAt").isNotEmpty())
                .andExpect(jsonPath("$.history[0].changedByName").doesNotExist());
    }

    @Test
    void customerCanReadOwnOrderListAndDetail() throws Exception {
        ShopOrderService.CustomerOrderSummary summary = new ShopOrderService.CustomerOrderSummary(
                "order-string-1", "O-000001", "Laundry One", "Wash", new java.math.BigDecimal("2.00"),
                new java.math.BigDecimal("50.00"), LaundryStage.RECEIVED, "UNPAID", null, null, java.time.Instant.now(), "kg");
        when(orders.listMyOrders(any(), eq(0))).thenReturn(new ShopOrderService.CustomerOrdersPage(List.of(summary), 0, 20, 1, 1));
        when(orders.getMyOrder(any(), eq("order-string-1"))).thenReturn(new ShopOrderService.CustomerOrderDetail(summary, List.of(), List.of()));

        mvc.perform(get("/api/me/orders").with(authentication(auth("CUSTOMER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].orderCode").value("O-000001"))
                .andExpect(jsonPath("$.content[0].unit").value("kg"))
                .andExpect(jsonPath("$.content[0].customerName").doesNotExist())
                .andExpect(jsonPath("$.content[0].mobile").doesNotExist());
        mvc.perform(get("/api/me/orders/order-string-1").with(authentication(auth("CUSTOMER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.shopName").value("Laundry One"));
        verify(orders).listMyOrders(any(), eq(0));
        verify(orders).getMyOrder(any(), eq("order-string-1"));
    }

    @Test
    void otherRolesCannotReadCustomerOrders() throws Exception {
        for (String role : List.of("PLATFORM_ADMIN", "OWNER", "STAFF")) {
            mvc.perform(get("/api/me/orders").with(authentication(auth(role)))).andExpect(status().isForbidden());
            mvc.perform(get("/api/me/orders/order-string-1").with(authentication(auth(role)))).andExpect(status().isForbidden());
            mvc.perform(post("/api/me/orders/order-string-1/payments/paypal-order").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/me/orders/order-string-1/payments/paypal-capture").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void customerCanCreateAndCapturePayPalPaymentForOwnOrder() throws Exception {
        ShopOrderService.OrderSummary pending = new ShopOrderService.OrderSummary(
                "order-string-1", "shop-1", "O-000001", "Customer", "639171234567",
                "user-string-1", "svc-1", "Wash", new java.math.BigDecimal("2.00"),
                new java.math.BigDecimal("100.00"), "", LaundryStage.RECEIVED, java.time.Instant.now(),
                "staff-1", "PENDING", "PAYPAL", null, null, null, "PP-CUST-1",
                "https://www.sandbox.paypal.com/checkoutnow?token=PP-CUST-1", 10000L, "order-string-1");
        ShopOrderService.OrderSummary paid = new ShopOrderService.OrderSummary(
                "order-string-1", "shop-1", "O-000001", "Customer", "639171234567",
                "user-string-1", "svc-1", "Wash", new java.math.BigDecimal("2.00"),
                new java.math.BigDecimal("100.00"), "", LaundryStage.RECEIVED, java.time.Instant.now(),
                "staff-1", "PAID", "PAYPAL", java.time.Instant.now(), "PAYPAL", "PayPal Sandbox");
        when(orders.createCustomerPayPalOrder(any(), eq("order-string-1"), any()))
                .thenReturn(new ShopOrderService.PayPalOrderResult(pending, "PP-CUST-1",
                        "https://www.sandbox.paypal.com/checkoutnow?token=PP-CUST-1"));
        when(orders.captureCustomerPayPalOrder(any(), eq("order-string-1"), any()))
                .thenReturn(paid);

        mvc.perform(post("/api/me/orders/order-string-1/payments/paypal-order")
                        .with(authentication(auth("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"00000000-0000-4000-8000-000000000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paypalOrderId").value("PP-CUST-1"))
                .andExpect(jsonPath("$.approvalUrl").value("https://www.sandbox.paypal.com/checkoutnow?token=PP-CUST-1"));

        mvc.perform(post("/api/me/orders/order-string-1/payments/paypal-capture")
                        .with(authentication(auth("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paypalOrderId\":\"PP-CUST-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
    }

    private static UsernamePasswordAuthenticationToken auth(String role) {
        FirebasePrincipal principal = new FirebasePrincipal("firebase-uid", "person@example.com", "Person",
                "user-string-1", role, null, null);
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"), new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
