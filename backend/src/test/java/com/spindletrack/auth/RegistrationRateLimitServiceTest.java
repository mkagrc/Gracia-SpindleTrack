package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class RegistrationRateLimitServiceTest {
    @Test
    void remoteAddressesAreStoredAsStableSha256HexHashes() {
        String first = RegistrationRateLimitService.hashAddress("192.0.2.10");
        String second = RegistrationRateLimitService.hashAddress("192.0.2.10");

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNotEquals("192.0.2.10", first);
    }
}
