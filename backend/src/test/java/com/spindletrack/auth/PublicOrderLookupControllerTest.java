package com.spindletrack.auth;

import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@WebMvcTest(PublicOrderLookupController.class)
@Import(SecurityConfig.class)
class PublicOrderLookupControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PublicOrderLookupService lookups;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository users;

    @Test
    void exactLookupRouteIsAnonymousAndReturnsOnlyTheConstrainedTrackingPayload() throws Exception {
        when(lookups.lookup("O-000123", "639171234567", "127.0.0.1"))
                .thenReturn(new PublicOrderLookupService.WalkInOrder("O-000123", "********4567",
                        LaundryStage.READY, "UNPAID", List.of(), List.of()));

        mvc.perform(get("/api/public/orders/lookup").param("orderCode", "O-000123")
                        .param("mobile", "639171234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value("O-000123"))
                .andExpect(jsonPath("$.maskedMobile").value("********4567"))
                .andExpect(jsonPath("$.currentStage").value("READY"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.totalPrice").doesNotExist())
                .andExpect(jsonPath("$.shopId").doesNotExist());

        mvc.perform(get("/api/public/orders/other"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lookupFailuresUseTheSameGenericMessage() throws Exception {
        when(lookups.lookup("bad", "bad", "127.0.0.1")).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Order could not be found. Check the order code and mobile number."));

        mvc.perform(get("/api/public/orders/lookup").param("orderCode", "bad").param("mobile", "bad"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order could not be found. Check the order code and mobile number."));
    }
}
