package com.spindletrack.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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

@WebMvcTest(ShopOrderController.class)
@Import(SecurityConfig.class)
class ShopOrderControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShopOrderService orders;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository users;

    @Test
    void staffCanCreateAdvanceAndListAndOwnerCanOnlyListAndRead() throws Exception {
        ShopOrderService.OrderSummary summary = new ShopOrderService.OrderSummary("order-id-a7", "shop-string-A", "O-000001",
                "A Person", "639171234567", null, List.of(), BigDecimal.ZERO, "", LaundryStage.RECEIVED, Instant.now(), "staff-user");
        when(orders.create(any(), any())).thenReturn(summary);
        when(orders.advance(any(), eq("order-id-a7"))).thenReturn(summary);
        when(orders.list(any(), eq(null), eq(null), eq(0))).thenReturn(new ShopOrderService.OrdersPage(List.of(summary), 0, 20, 1, 1));
        when(orders.get(any(), eq("order-id-a7"))).thenReturn(new ShopOrderService.OrderDetail(summary, List.of()));

        mvc.perform(post("/api/shops/orders").with(authentication(auth("STAFF")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"A Person\",\"mobile\":\"+63 917 123 4567\",\"items\":[{\"serviceId\":\"svc-string-1\",\"quantity\":2}],\"notes\":\"\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/shops/orders/order-id-a7/advance").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shops/orders").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shops/orders/order-id-a7").with(authentication(auth("OWNER"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shops/orders").with(authentication(auth("OWNER"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/shops/orders").with(authentication(auth("OWNER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"items\":[{\"serviceId\":\"svc-string-1\",\"quantity\":1}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/shops/orders/order-id-a7/advance").with(authentication(auth("OWNER"))))
                .andExpect(status().isForbidden());

        verify(orders).create(any(), any());
        verify(orders).advance(any(), eq("order-id-a7"));
        verify(orders, times(2)).list(any(), eq(null), eq(null), eq(0));
        verify(orders).get(any(), eq("order-id-a7"));
    }

    @Test
    void adminCustomerAndRemovedStaffCannotAccessShopOrders() throws Exception {
        for (String role : List.of("PLATFORM_ADMIN", "CUSTOMER")) {
            mvc.perform(get("/api/shops/orders").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/shops/orders/order-id-a7").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/shops/orders").with(authentication(auth(role)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"items\":[{\"serviceId\":\"svc-id\",\"quantity\":1}]}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/shops/orders/order-id-a7/advance").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void staffCreateRequiresAtLeastOneItemAndPositiveQuantity() throws Exception {
        mvc.perform(post("/api/shops/orders").with(authentication(auth("STAFF")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"items\":[{\"serviceId\":\"svc-id\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    private static UsernamePasswordAuthenticationToken auth(String role) {
        FirebasePrincipal principal = new FirebasePrincipal("uid", "staff@example.com", "Account", "user-id-77", role, "shop-string-A", "ACTIVE");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"), new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
