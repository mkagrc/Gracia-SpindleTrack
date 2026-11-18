package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PresencePathTest {
    @Test
    void eachBackendRoleHasItsOwnPresencePath() {
        assertEquals("presence/admin/u1", PresencePath.forUser("PLATFORM_ADMIN", "u1"));
        assertEquals("presence/owners/u1", PresencePath.forUser("OWNER", "u1"));
        assertEquals("presence/staff/u1", PresencePath.forUser("STAFF", "u1"));
        assertEquals("presence/customers/u1", PresencePath.forUser("CUSTOMER", "u1"));
    }

    @Test
    void unknownOrClientChosenRoleCannotSelectAPresencePath() {
        assertThrows(ResponseStatusException.class, () -> PresencePath.forUser("OWNER_ADMIN", "u1"));
        assertThrows(ResponseStatusException.class, () -> PresencePath.forUser(null, "u1"));
    }
}
