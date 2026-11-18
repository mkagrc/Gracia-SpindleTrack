package com.spindletrack.auth;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(AdminShopController.class)
@Import(SecurityConfig.class)
class AdminShopControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminShopService shops;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository users;

    private static UsernamePasswordAuthenticationToken auth(String role) {
        FirebasePrincipal principal = new FirebasePrincipal("u", "a@example.com", "Admin", "user-7", role, null, null);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void everyAdminRouteIsForbiddenForOwnerStaffAndCustomer() throws Exception {
        List<String> gets = List.of("/api/admin/shops", "/api/admin/shops/shop-a");
        List<String> posts = List.of("/api/admin/shops/shop-a/approve", "/api/admin/shops/shop-a/reject", "/api/admin/shops/shop-a/deactivate", "/api/admin/shops/shop-a/activate");
        for (String role : List.of("OWNER", "STAFF", "CUSTOMER")) {
            for (String path : gets) mvc.perform(get(path).with(authentication(auth(role)))).andExpect(status().isForbidden());
            for (String path : posts) {
                var request = post(path).with(authentication(auth(role)));
                if (path.endsWith("/reject")) request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No permit\"}");
                mvc.perform(request).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void platformAdminCanCallAllAdminRoutes() throws Exception {
        when(shops.list(null, null, 0)).thenReturn(new AdminShopService.PageResult(List.of(), 0, 20, 0));
        mvc.perform(get("/api/admin/shops").with(authentication(auth("PLATFORM_ADMIN")))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/shops/shop-a").with(authentication(auth("PLATFORM_ADMIN")))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/shops/shop-a/approve").with(authentication(auth("PLATFORM_ADMIN")))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/shops/shop-a/reject").with(authentication(auth("PLATFORM_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing registration\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/shops/shop-a/deactivate").with(authentication(auth("PLATFORM_ADMIN")))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/shops/shop-a/activate").with(authentication(auth("PLATFORM_ADMIN")))).andExpect(status().isOk());
        verify(shops).transition("shop-a", "APPROVED", null, "user-7");
        verify(shops).transition("shop-a", "REJECTED", "Missing registration", "user-7");
        verify(shops).transition("shop-a", "DEACTIVATED", null, "user-7");
        verify(shops).transition("shop-a", "ACTIVE", null, "user-7");
    }

    @Test
    void rejectReasonIsRequiredAndLimitedToFiveHundredCharacters() throws Exception {
        mvc.perform(post("/api/admin/shops/shop-a/reject").with(authentication(auth("PLATFORM_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        String tooLong = "x".repeat(501);
        mvc.perform(post("/api/admin/shops/shop-a/reject").with(authentication(auth("PLATFORM_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
