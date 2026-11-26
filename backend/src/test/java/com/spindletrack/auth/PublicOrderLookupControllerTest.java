package com.spindletrack.auth;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;

@WebMvcTest(PublicOrderLookupController.class)
@Import(SecurityConfig.class)
class PublicOrderLookupControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PublicOrderLookupService lookups;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository users;
    @MockitoBean PublicClientAddressResolver clientAddresses;

    @Test
    void exactLookupRouteIsAnonymousAndReturnsOnlyTheConstrainedTrackingPayload() throws Exception {
        when(lookups.lookup("O-000123", "639171234567", "127.0.0.1"))
                .thenReturn(new PublicOrderLookupService.WalkInOrder("O-000123", "********4567",
                        LaundryStage.READY, "UNPAID", List.of(), new java.math.BigDecimal("42.00"), "kg"));
        when(clientAddresses.resolve(any(HttpServletRequest.class))).thenReturn("127.0.0.1");

        mvc.perform(get("/api/public/orders/lookup").param("orderCode", "O-000123")
                        .param("mobile", "639171234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value("O-000123"))
                .andExpect(jsonPath("$.maskedMobile").value("********4567"))
                .andExpect(jsonPath("$.currentStage").value("READY"))
                .andExpect(jsonPath("$.unit").value("kg"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.totalPrice").doesNotExist())
                .andExpect(jsonPath("$.shopId").doesNotExist())
                .andExpect(jsonPath("$.paymentHistory").doesNotExist());

        mvc.perform(get("/api/public/orders/other"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lookupFailuresUseTheSameGenericMessage() throws Exception {
        when(lookups.lookup("bad", "bad", "127.0.0.1")).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Order could not be found. Check the order code and mobile number."));
        when(clientAddresses.resolve(any(HttpServletRequest.class))).thenReturn("127.0.0.1");

        mvc.perform(get("/api/public/orders/lookup").param("orderCode", "bad").param("mobile", "bad"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order could not be found. Check the order code and mobile number."));
    }

    @Test
    void publicPayPalOrderAndCaptureArePermitAllAndNeverExposePrivateFields() throws Exception {
        when(clientAddresses.resolve(any(HttpServletRequest.class))).thenReturn("127.0.0.1");
        when(lookups.createPublicPayPalOrder(any(), any())).thenReturn(
                new PublicOrderLookupService.PublicPayPalOrderResponse(
                        "O-000123", "PENDING", "PP-PUB-1", "https://www.sandbox.paypal.com/checkoutnow?token=PP-PUB-1"));
        when(lookups.capturePublicPayPalOrder(any(), any())).thenReturn(
                new PublicOrderLookupService.PublicPayPalCaptureResponse("O-000123", "PAID", "PP-PUB-1"));

        mvc.perform(post("/api/public/orders/paypal-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderCode\":\"O-000123\",\"mobile\":\"639171234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value("O-000123"))
                .andExpect(jsonPath("$.paypalOrderId").value("PP-PUB-1"))
                .andExpect(jsonPath("$.approvalUrl").value("https://www.sandbox.paypal.com/checkoutnow?token=PP-PUB-1"))
                .andExpect(jsonPath("$.totalPrice").doesNotExist())
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.mobile").doesNotExist())
                .andExpect(jsonPath("$.shopId").doesNotExist());

        mvc.perform(post("/api/public/orders/paypal-capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderCode\":\"O-000123\",\"mobile\":\"639171234567\",\"paypalOrderId\":\"PP-PUB-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.totalPrice").doesNotExist())
                .andExpect(jsonPath("$.mobile").doesNotExist());
    }
}
