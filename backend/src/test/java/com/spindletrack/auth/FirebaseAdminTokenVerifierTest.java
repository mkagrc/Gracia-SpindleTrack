package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FirebaseAdminTokenVerifierTest {
    @Test
    void verifiedFirebaseEmailProducesIdentity() {
        FirebaseIdentity identity = FirebaseAdminTokenVerifier.identityFromClaims("uid-1", Map.of(
                "email", "owner@example.com",
                "email_verified", true,
                "name", "Shop Owner"));

        assertEquals("uid-1", identity.uid());
        assertEquals("owner@example.com", identity.email());
        assertEquals("Shop Owner", identity.displayName());
    }

    @Test
    void unverifiedFirebaseEmailIsRejected() {
        FirebaseAuthenticationException exception = assertThrows(FirebaseAuthenticationException.class,
                () -> FirebaseAdminTokenVerifier.identityFromClaims("uid-2", Map.of(
                        "email", "owner@example.com",
                        "email_verified", false)));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("Please verify your email address before signing in.", exception.getMessage());
    }

    @Test
    void missingEmailIsRejected() {
        FirebaseAuthenticationException exception = assertThrows(FirebaseAuthenticationException.class,
                () -> FirebaseAdminTokenVerifier.identityFromClaims("uid-3", Map.of("email_verified", true)));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
    }
}
