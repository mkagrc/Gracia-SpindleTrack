package com.spindletrack.auth;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.spindletrack.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OwnerStaffPresenceController.class)
@Import(SecurityConfig.class)
class OwnerStaffPresenceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserPresenceService presence;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository accounts;

    private static UsernamePasswordAuthenticationToken auth(String role, String shopId) {
        FirebasePrincipal principal = new FirebasePrincipal("uid-1", "owner@example.com", "Owner", "user-1", role, shopId, "ACTIVE");
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void ownerEndpointUsesOnlyPrincipalShopAndReturnsNoEmails() throws Exception {
        mvc.perform(get("/api/shops/mine/staff-presence").with(authentication(auth("OWNER", "own-shop"))))
                .andExpect(status().isOk());
        verify(presence).staffForShop("own-shop");
    }

    @Test
    void platformAdminStaffAndCustomerCannotCallOwnerPresenceEndpoint() throws Exception {
        for (String role : List.of("PLATFORM_ADMIN", "STAFF", "CUSTOMER")) {
            mvc.perform(get("/api/shops/mine/staff-presence").with(authentication(auth(role, "other-shop"))))
                    .andExpect(status().isForbidden());
        }
    }
}
