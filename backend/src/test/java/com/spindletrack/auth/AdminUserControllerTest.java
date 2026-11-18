package com.spindletrack.auth;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

@WebMvcTest(AdminUserController.class)
@Import(SecurityConfig.class)
class AdminUserControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserPresenceService users;
    @MockitoBean FirebaseTokenVerifier verifier;
    @MockitoBean UserAccountRepository accounts;

    private static UsernamePasswordAuthenticationToken auth(String role) {
        FirebasePrincipal principal = new FirebasePrincipal("uid-1", "person@example.com", "Person", "user-1", role, "shop-1", "ACTIVE");
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void platformAdminCanListUsersWithRoleAndPageFilters() throws Exception {
        when(users.listUsers("STAFF", 2)).thenReturn(new UserPresenceService.AdminPage(List.of(), 2, 20, 0));
        mvc.perform(get("/api/admin/users").param("role", "STAFF").param("page", "2")
                .with(authentication(auth("PLATFORM_ADMIN")))).andExpect(status().isOk());
        verify(users).listUsers("STAFF", 2);
    }

    @Test
    void ownerStaffAndCustomerCannotCallAdminUsersEndpoint() throws Exception {
        for (String role : List.of("OWNER", "STAFF", "CUSTOMER")) {
            mvc.perform(get("/api/admin/users").with(authentication(auth(role)))).andExpect(status().isForbidden());
        }
    }
}
