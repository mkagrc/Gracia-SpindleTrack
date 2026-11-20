package com.spindletrack.auth;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

@WebMvcTest(OwnerOperationsController.class)
@Import(SecurityConfig.class)
class OwnerOperationsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OwnerOperationsService operations;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository users;

    private static UsernamePasswordAuthenticationToken auth(String role) {
        FirebasePrincipal principal = new FirebasePrincipal("uid", "owner@example.com", "Owner", "user-1", role, "shop-A", "ACTIVE");
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void ownerCanReadReportsAndUnclaimedList() throws Exception {
        when(operations.unclaimed(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(operations.report(org.mockito.ArgumentMatchers.any())).thenReturn(new OwnerOperationsService.OwnerReport(
                new OwnerOperationsService.DailyMetric("2026-11-21", 1, BigDecimal.TEN),
                new OwnerOperationsService.MonthlyMetric("2026-11", 2, BigDecimal.valueOf(20)), List.of()));
        mvc.perform(get("/api/shops/mine/unclaimed").with(authentication(auth("OWNER")))).andExpect(status().isOk());
        mvc.perform(get("/api/shops/mine/reports").with(authentication(auth("OWNER")))).andExpect(status().isOk());
        verify(operations).unclaimed(org.mockito.ArgumentMatchers.any());
        verify(operations).report(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allNonOwnersAreForbiddenFromBothEndpoints() throws Exception {
        for (String role : List.of("STAFF", "CUSTOMER", "PLATFORM_ADMIN")) {
            mvc.perform(get("/api/shops/mine/unclaimed").with(authentication(auth(role)))).andExpect(status().isForbidden());
            mvc.perform(get("/api/shops/mine/reports").with(authentication(auth(role)))).andExpect(status().isForbidden());
        }
    }
}
