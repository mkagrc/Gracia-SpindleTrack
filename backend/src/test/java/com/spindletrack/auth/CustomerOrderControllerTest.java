package com.spindletrack.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.spindletrack.LaundryStage;
import com.spindletrack.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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
    void customerCanReadOwnOrderListAndDetail() throws Exception {
        ShopOrderService.CustomerOrderSummary summary = new ShopOrderService.CustomerOrderSummary(
                "order-string-1", "O-000001", "Laundry One", "Wash", new java.math.BigDecimal("2.00"),
                new java.math.BigDecimal("50.00"), LaundryStage.RECEIVED, "UNPAID", null, null, java.time.Instant.now());
        when(orders.listMyOrders(any(), eq(0))).thenReturn(new ShopOrderService.CustomerOrdersPage(List.of(summary), 0, 20, 1, 1));
        when(orders.getMyOrder(any(), eq("order-string-1"))).thenReturn(new ShopOrderService.CustomerOrderDetail(summary, List.of(), List.of()));

        mvc.perform(get("/api/me/orders").with(authentication(auth("CUSTOMER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].orderCode").value("O-000001"))
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
        }
    }

    private static UsernamePasswordAuthenticationToken auth(String role) {
        FirebasePrincipal principal = new FirebasePrincipal("firebase-uid", "person@example.com", "Person",
                "user-string-1", role, null, null);
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"), new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
