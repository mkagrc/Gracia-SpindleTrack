package com.spindletrack.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
                "A Person", "639171234567", null, "svc-id", "Wash", new BigDecimal("2.00"), new BigDecimal("30.00"),
                "", LaundryStage.RECEIVED, Instant.now(), "staff-user", "UNPAID", null, null, null, null);
        when(orders.create(any(), any())).thenReturn(summary);
        when(orders.advance(any(), eq("order-id-a7"))).thenReturn(summary);
        when(orders.list(any(), eq(null), eq(null), eq(0))).thenReturn(new ShopOrderService.OrdersPage(List.of(summary), 0, 20, 1, 1));
        when(orders.get(any(), eq("order-id-a7"))).thenReturn(new ShopOrderService.OrderDetail(summary, List.of(), List.of()));
        when(orders.changePrice(any(), eq("order-id-a7"), any())).thenReturn(summary);

        mvc.perform(post("/api/shops/orders").with(authentication(auth("STAFF")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"A Person\",\"mobile\":\"+63 917 123 4567\",\"serviceId\":\"svc-string-1\",\"kilos\":2,\"totalPrice\":30.00,\"notes\":\"\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/shops/orders/order-id-a7/advance").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/shops/orders/order-id-a7/price").with(authentication(auth("STAFF")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"totalPrice\":35.00}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shops/orders").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shops/orders/order-id-a7").with(authentication(auth("OWNER"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shops/orders").with(authentication(auth("OWNER"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/shops/orders").with(authentication(auth("OWNER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"serviceId\":\"svc-id\",\"kilos\":1,\"totalPrice\":10}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/shops/orders/order-id-a7/price").with(authentication(auth("OWNER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"totalPrice\":35.00}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/shops/orders/order-id-a7/advance").with(authentication(auth("OWNER"))))
                .andExpect(status().isForbidden());

        verify(orders).create(any(), any());
        verify(orders).advance(any(), eq("order-id-a7"));
        verify(orders, times(2)).list(any(), eq(null), eq(null), eq(0));
        verify(orders).get(any(), eq("order-id-a7"));
        verify(orders).changePrice(any(), eq("order-id-a7"), any());
    }

    @Test
    void adminCustomerAndRemovedStaffCannotAccessShopOrders() throws Exception {
        for (String role : List.of("PLATFORM_ADMIN", "CUSTOMER")) {
            mvc.perform(get("/api/shops/orders").with(authentication(auth(role)))).andExpect(status().isForbidden());
            mvc.perform(get("/api/shops/orders/order-id-a7").with(authentication(auth(role)))).andExpect(status().isForbidden());
            mvc.perform(post("/api/shops/orders").with(authentication(auth(role))).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"serviceId\":\"svc-id\",\"kilos\":1,\"totalPrice\":10}"))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/api/shops/orders/order-id-a7/price").with(authentication(auth(role))).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"totalPrice\":35.00}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/shops/orders/order-id-a7/advance").with(authentication(auth(role)))).andExpect(status().isForbidden());
        }
    }

    @Test
    void createRejectsInvalidKilosPricesAndBlankServiceAtRequestValidation() throws Exception {
        for (String body : List.of(
                "{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"serviceId\":\"svc-id\",\"kilos\":0,\"totalPrice\":30}",
                "{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"serviceId\":\"svc-id\",\"kilos\":-1,\"totalPrice\":30}",
                "{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"serviceId\":\"svc-id\",\"kilos\":1.001,\"totalPrice\":30}",
                "{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"serviceId\":\"svc-id\",\"kilos\":1,\"totalPrice\":-1}",
                "{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"serviceId\":\"svc-id\",\"kilos\":1,\"totalPrice\":30.001}",
                "{\"customerName\":\"A Person\",\"mobile\":\"639171234567\",\"serviceId\":\" \",\"kilos\":1,\"totalPrice\":30}")) {
            mvc.perform(post("/api/shops/orders").with(authentication(auth("STAFF")))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    @Test
    void pricePatchRejectsNegativeOrOverPrecisionPriceAtRequestValidation() throws Exception {
        for (String body : List.of("{\"totalPrice\":-0.01}", "{\"totalPrice\":30.001}")) {
            mvc.perform(patch("/api/shops/orders/order-id-a7/price").with(authentication(auth("STAFF")))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        }
    }
    @Test
    void cashPaymentEndpointIsStaffOnly() throws Exception {
        ShopOrderService.OrderSummary paid = new ShopOrderService.OrderSummary("order-id-a7", "shop-string-A", "O-000001",
                "A Person", "639171234567", null, "svc-id", "Wash", new BigDecimal("2.00"), new BigDecimal("30.00"),
                "", LaundryStage.RECEIVED, Instant.now(), "staff-user", "PAID", "CASH", Instant.now(), "staff-uid", "Staff");
        when(orders.recordCashPayment(any(), eq("order-id-a7"))).thenReturn(paid);
        mvc.perform(post("/api/shops/orders/order-id-a7/payments/cash").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH"));
        mvc.perform(post("/api/shops/orders/order-id-a7/payments/cash").with(authentication(auth("OWNER"))))
                .andExpect(status().isForbidden());
        for (String role : List.of("PLATFORM_ADMIN", "CUSTOMER")) {
            mvc.perform(post("/api/shops/orders/order-id-a7/payments/cash").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void patchPricePreflightIsAllowedFromBothConfiguredOrigins() throws Exception {
        for (String origin : List.of("http://localhost:5173", "http://127.0.0.1:5173")) {
            mvc.perform(options("/api/shops/orders/order-id-a7/price")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "PATCH")
                    .header("Access-Control-Request-Headers", "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
        }
    }

    @Test
    void onlinePaymentLinkConfirmationAndCancellationAreStaffOnly() throws Exception {
        ShopOrderService.OrderSummary summary = new ShopOrderService.OrderSummary("order-id-a7", "shop-string-A", "O-000001",
                "A Person", "639171234567", null, "svc-id", "Wash", new BigDecimal("2.00"), new BigDecimal("30.00"),
                "", LaundryStage.RECEIVED, Instant.now(), "staff-user", "PENDING", "ONLINE", null, null, null);
        when(orders.createPaymentLink(any(), eq("order-id-a7"))).thenReturn(new ShopOrderService.PaymentLinkResult(summary, "link-1", "https://pm.link/test"));
        when(orders.confirmOnlinePayment(any(), eq("order-id-a7"))).thenReturn(summary);
        when(orders.cancelPaymentLink(any(), eq("order-id-a7"))).thenReturn(summary);
        mvc.perform(post("/api/shops/orders/order-id-a7/payments/paymongo-link").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentLinkUrl").value("https://pm.link/test"));
        mvc.perform(post("/api/shops/orders/order-id-a7/payments/confirm-online").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/shops/orders/order-id-a7/payments/cancel-link").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk());
        for (String role : List.of("OWNER", "PLATFORM_ADMIN", "CUSTOMER")) {
            mvc.perform(post("/api/shops/orders/order-id-a7/payments/paymongo-link").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/shops/orders/order-id-a7/payments/confirm-online").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/shops/orders/order-id-a7/payments/cancel-link").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void exactEmailCustomerMatchIsStaffOnlyAndReturnsOnlyName() throws Exception {
        when(orders.findCustomerForOrder(any(), eq("customer@example.com")))
                .thenReturn(new ShopOrderService.CustomerMatch("Verified Customer"));
        mvc.perform(get("/api/shops/orders/customer-match").param("email", "customer@example.com")
                        .with(authentication(auth("STAFF"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Verified Customer"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.mobile").doesNotExist());
        for (String role : List.of("OWNER", "PLATFORM_ADMIN", "CUSTOMER")) {
            mvc.perform(get("/api/shops/orders/customer-match").param("email", "customer@example.com")
                    .with(authentication(auth(role)))).andExpect(status().isForbidden());
        }
        verify(orders).findCustomerForOrder(any(), eq("customer@example.com"));
    }

    private static UsernamePasswordAuthenticationToken auth(String role) {
        FirebasePrincipal principal = new FirebasePrincipal("uid", "staff@example.com", "Account", "user-id-77", role, "shop-string-A", "ACTIVE");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"), new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
