package com.spindletrack.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import com.spindletrack.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(PublicShopDirectoryController.class)
@Import(SecurityConfig.class)
class PublicShopDirectoryControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean PublicShopDirectoryService directory;
    @MockitoBean RegistrationRateLimitService rateLimit;
    @MockitoBean PublicClientAddressResolver  addressResolver;

    // Required by SecurityConfig / FirebaseBearerAuthenticationFilter
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository  users;

    // ── Search endpoint ───────────────────────────────────────────────────────

    @Test
    void searchEndpointIsPublicAndReturnsShopList() throws Exception {
        when(addressResolver.resolve(any())).thenReturn("127.0.0.1");
        when(directory.search(anyString(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(new PublicShopDirectoryService.PublicShopSummary(
                        "shop-1", "Alpha Wash", "Manila", "123456789",
                        14.5, 121.0, null)));

        mvc.perform(get("/api/public/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shopId").value("shop-1"))
                .andExpect(jsonPath("$[0].name").value("Alpha Wash"));
    }

    @Test
    void searchPassesQueryAndCoordinatesToService() throws Exception {
        when(addressResolver.resolve(any())).thenReturn("127.0.0.1");
        when(directory.search(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(new PublicShopDirectoryService.PublicShopSummary(
                        "shop-2", "Near Wash", "Pasig", "987654321",
                        14.1, 121.1, BigDecimal.valueOf(1.234))));

        mvc.perform(get("/api/public/shops?search=near&lat=14.5&lng=121.0&page=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].distanceKm").value(1.234));
    }

    @Test
    void searchReturns429WhenRateLimitExceeded() throws Exception {
        when(addressResolver.resolve(any())).thenReturn("198.51.100.7");
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests."))
                .when(rateLimit).recordPublicShopDirectoryAttempt(anyString());

        mvc.perform(get("/api/public/shops"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void searchResponseNeverContainsOwnerOrDocumentFields() throws Exception {
        when(addressResolver.resolve(any())).thenReturn("127.0.0.1");
        when(directory.search(anyString(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(new PublicShopDirectoryService.PublicShopSummary(
                        "shop-3", "Shop Three", "Makati", "111222333",
                        null, null, null)));

        String body = mvc.perform(get("/api/public/shops"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Confirm no sensitive fields are in the JSON
        for (String forbidden : List.of("email", "owner", "document", "staff", "order")) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    body.toLowerCase().contains(forbidden),
                    "Response must not contain field: " + forbidden);
        }
    }

    // ── Detail endpoint ───────────────────────────────────────────────────────

    @Test
    void detailEndpointIsPublicAndReturnsShopWithServices() throws Exception {
        when(addressResolver.resolve(any())).thenReturn("127.0.0.1");
        when(directory.detail("shop-A"))
                .thenReturn(new PublicShopDirectoryService.PublicShopDetail(
                        "shop-A", "Alpha Wash", "Manila", "123456789", 14.5, 121.0,
                        List.of(new PublicShopDirectoryService.PublicServiceEntry(
                                "Wash & Dry", "kg", BigDecimal.valueOf(80.00)))));

        mvc.perform(get("/api/public/shops/shop-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopId").value("shop-A"))
                .andExpect(jsonPath("$.services[0].name").value("Wash & Dry"))
                .andExpect(jsonPath("$.services[0].referencePrice").value(80.00));
    }

    @Test
    void detailReturns404ForNonActiveOrUnknownShop() throws Exception {
        when(addressResolver.resolve(any())).thenReturn("127.0.0.1");
        when(directory.detail("shop-X"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found."));

        mvc.perform(get("/api/public/shops/shop-X"))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailReturns429WhenRateLimitExceeded() throws Exception {
        when(addressResolver.resolve(any())).thenReturn("198.51.100.7");
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests."))
                .when(rateLimit).recordPublicShopDirectoryAttempt(anyString());

        mvc.perform(get("/api/public/shops/shop-A"))
                .andExpect(status().isTooManyRequests());
    }
}
