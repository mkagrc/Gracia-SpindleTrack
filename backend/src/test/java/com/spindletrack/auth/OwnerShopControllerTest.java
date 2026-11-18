package com.spindletrack.auth;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

@WebMvcTest(OwnerShopController.class)
@Import(SecurityConfig.class)
class OwnerShopControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OwnerShopService shops;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository users;

    private static UsernamePasswordAuthenticationToken auth(String role, String shopId) {
        FirebasePrincipal principal = new FirebasePrincipal("uid", "owner@example.com", "Owner", "user-4", role, shopId, "ACTIVE");
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void ownerCanReadAndEditOnlyProfileAssociatedWithAuthenticatedShop() throws Exception {
        when(shops.getMine("shop-12")).thenReturn(new OwnerShopService.ShopProfile("shop-12", "Mine", "Address", "1234567", "ACTIVE"));
        when(shops.updateMine(org.mockito.ArgumentMatchers.eq("shop-12"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OwnerShopService.ShopProfile("shop-12", "New Name", "New Address", "7654321", "ACTIVE"));
        mvc.perform(get("/api/shops/mine").with(authentication(auth("OWNER", "shop-12")))).andExpect(status().isOk());
        mvc.perform(put("/api/shops/mine").with(authentication(auth("OWNER", "shop-12"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Name\",\"address\":\"New Address\",\"contactNumber\":\"7654321\"}"))
                .andExpect(status().isOk());
        verify(shops).getMine("shop-12");
        verify(shops).updateMine(org.mockito.ArgumentMatchers.eq("shop-12"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonOwnersCannotReadOrEditOwnerProfile() throws Exception {
        for (String role : List.of("PLATFORM_ADMIN", "STAFF", "CUSTOMER")) {
            mvc.perform(get("/api/shops/mine").with(authentication(auth(role, "shop-12")))).andExpect(status().isForbidden());
            mvc.perform(put("/api/shops/mine").with(authentication(auth(role, "shop-12"))).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Hacked\",\"address\":\"Other\",\"contactNumber\":\"1234567\"}"))
                    .andExpect(status().isForbidden());
        }
    }
}
