package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserAccountRepository users;

    @Test
    void configuredAdminEmailBootstrapsPlatformAdminOnFirstSession() {
        FirebaseIdentity identity = new FirebaseIdentity("admin-uid", "Admin@Example.com", "Platform Admin");
        UserAccount admin = account("admin-uid", "admin@example.com", "PLATFORM_ADMIN", null);
        when(users.findByFirebaseUid(identity.uid())).thenReturn(Optional.empty(), Optional.of(admin));
        when(users.findByEmail(identity.email())).thenReturn(Optional.empty());

        AuthSessionResponse response = new AuthService(users, " admin@example.com ").createOrLoadSession(identity);

        verify(users).insertFirebaseUser(identity.uid(), "Platform Admin", identity.email(), "PLATFORM_ADMIN");
        assertEquals("PLATFORM_ADMIN", response.role());
        assertEquals(null, response.shopId());
    }

    @Test
    void regularFirstSignInCreatesCustomerAccount() {
        FirebaseIdentity identity = new FirebaseIdentity("customer-uid", "customer@example.com", "New Customer");
        UserAccount customer = account("customer-uid", identity.email(), "CUSTOMER", null);
        when(users.findByFirebaseUid(identity.uid())).thenReturn(Optional.empty(), Optional.of(customer));
        when(users.findByEmail(identity.email())).thenReturn(Optional.empty());

        AuthSessionResponse response = new AuthService(users, "admin@example.com").createOrLoadSession(identity);

        verify(users).insertFirebaseUser(identity.uid(), "New Customer", identity.email(), "CUSTOMER");
        assertEquals("CUSTOMER", response.role());
    }

    @Test
    void ordinarySignInCannotClaimAnUnlinkedSeedAccount() {
        FirebaseIdentity identity = new FirebaseIdentity("new-uid", "sample@example.com", "Sample");
        when(users.findByFirebaseUid(identity.uid())).thenReturn(Optional.empty());
        when(users.findByEmail(identity.email())).thenReturn(Optional.of(
                new UserAccount(3, null, "Sample User", identity.email(), "CUSTOMER", true, 1L, "ACTIVE")));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> new AuthService(users, "admin@example.com").createOrLoadSession(identity));

        assertEquals("Please use another account.", exception.getReason());
    }

    private static UserAccount account(String uid, String email, String role, Long shopId) {
        return new UserAccount(9L, uid, "Account", email, role, true, shopId, null);
    }
}
