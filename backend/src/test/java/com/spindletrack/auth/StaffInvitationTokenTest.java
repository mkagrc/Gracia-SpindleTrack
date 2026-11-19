package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StaffInvitationTokenTest {
    @Test
    void generatedTokenIsHighEntropyAndOnlyItsSha256HashIsStored() {
        String raw = StaffInvitationToken.create();
        String hash = StaffInvitationToken.hash(raw);

        assertEquals(43, raw.length());
        assertEquals(64, hash.length());
        assertNotEquals(raw, hash);
        assertEquals(hash, StaffInvitationToken.hash(raw));
        assertNotEquals(hash, StaffInvitationToken.hash(StaffInvitationToken.create()));
    }

    @Test
    void expirationBoundaryIsInclusiveAndNullIsExpired() {
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        assertFalse(StaffInvitationToken.expired(now.plusSeconds(1), now));
        assertTrue(StaffInvitationToken.expired(now, now));
        assertTrue(StaffInvitationToken.expired(now.minusSeconds(1), now));
        assertTrue(StaffInvitationToken.expired(null, now));
    }
}
