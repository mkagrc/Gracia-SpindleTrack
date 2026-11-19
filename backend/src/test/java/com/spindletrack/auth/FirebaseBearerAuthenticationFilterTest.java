package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class FirebaseBearerAuthenticationFilterTest {
    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void deactivatedStaffIsBlockedImmediatelyByTheFirestoreActiveFlag() throws Exception {
        FirebaseTokenVerifier tokenVerifier = mock(FirebaseTokenVerifier.class);
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        when(tokenVerifier.verify("signed-token")).thenReturn(new FirebaseIdentity("staff-uid", "staff@example.com", "Staff"));
        when(accounts.findByFirebaseUid("staff-uid")).thenReturn(Optional.of(
                new UserAccount("staff-user-id", "staff-uid", "Staff", "staff@example.com", "STAFF", false, "shop-string-A", "ACTIVE")));
        FirebaseBearerAuthenticationFilter filter = new FirebaseBearerAuthenticationFilter(tokenVerifier, accounts,
                new ObjectMapper(), "admin@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shops/services");
        request.addHeader("Authorization", "Bearer signed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reachedController = { false };

        filter.doFilterInternal(request, response, (servletRequest, servletResponse) -> reachedController[0] = true);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("This account is inactive."));
        assertFalse(reachedController[0]);
    }

    @Test
    void removedStaffUsesTheCurrentCustomerRoleRatherThanStaleTokenClaims() throws Exception {
        FirebaseTokenVerifier tokenVerifier = mock(FirebaseTokenVerifier.class);
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        when(tokenVerifier.verify("signed-token")).thenReturn(new FirebaseIdentity("staff-uid", "staff@example.com", "Staff"));
        when(accounts.findByFirebaseUid("staff-uid")).thenReturn(Optional.of(
                new UserAccount("staff-user-id", "staff-uid", "Staff", "staff@example.com", "CUSTOMER", true, null, null)));
        FirebaseBearerAuthenticationFilter filter = new FirebaseBearerAuthenticationFilter(tokenVerifier, accounts,
                new ObjectMapper(), "admin@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shops/mine/staff");
        request.addHeader("Authorization", "Bearer signed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (servletRequest, servletResponse) -> {
            var principal = SecurityContextHolder.getContext().getAuthentication();
            assertEquals("CUSTOMER", ((FirebasePrincipal) principal.getPrincipal()).role());
            assertFalse(principal.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_STAFF")));
        });

        assertEquals(200, response.getStatus());
    }
}
