package com.spindletrack.auth;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
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

@WebMvcTest(ShopServiceController.class)
@Import(SecurityConfig.class)
class ShopServiceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShopServiceCatalog services;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository users;

    @Test
    void ownerAndStaffCanListButStaffCannotCreateOrEditServices() throws Exception {
        when(services.list("shop-string-A", "OWNER")).thenReturn(List.of());
        when(services.list("shop-string-A", "STAFF")).thenReturn(List.of());
        mvc.perform(get("/api/shops/services").with(authentication(auth("OWNER"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shops/services").with(authentication(auth("STAFF"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/shops/services").with(authentication(auth("STAFF")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Wash\",\"pricePerUnit\":25,\"unit\":\"kg\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/shops/services/service-doc-id-ab12").with(authentication(auth("STAFF")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Wash\",\"pricePerUnit\":25,\"unit\":\"kg\"}"))
                .andExpect(status().isForbidden());
        verify(services).list("shop-string-A", "OWNER");
        verify(services).list("shop-string-A", "STAFF");
    }

    @Test
    void customersAndAdminsCannotReadShopServices() throws Exception {
        for (String role : List.of("CUSTOMER", "PLATFORM_ADMIN")) {
            mvc.perform(get("/api/shops/services").with(authentication(auth(role))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void ownerCanUpdateUsingOpaqueStringServiceId() throws Exception {
        when(services.update(eq("shop-string-A"), eq("svc-auto-id-abc"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ShopServiceCatalog.ShopService("svc-auto-id-abc", "Wash", BigDecimal.TEN, "kg", true, null));
        mvc.perform(put("/api/shops/services/svc-auto-id-abc").with(authentication(auth("OWNER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Wash\",\"pricePerUnit\":10,\"unit\":\"kg\"}"))
                .andExpect(status().isOk());
        verify(services).update(eq("shop-string-A"), eq("svc-auto-id-abc"), org.mockito.ArgumentMatchers.any());
    }

    private static UsernamePasswordAuthenticationToken auth(String role) {
        FirebasePrincipal principal = new FirebasePrincipal("uid", "owner@example.com", "Account", "user-id-77", role, "shop-string-A", "ACTIVE");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"), new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
