package com.spindletrack.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class PresencePath {
    private PresencePath() {}

    public static String groupForRole(String role) {
        return switch (role == null ? "" : role) {
            case "PLATFORM_ADMIN" -> "admin";
            case "OWNER" -> "owners";
            case "STAFF" -> "staff";
            case "CUSTOMER" -> "customers";
            default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has no valid presence role.");
        };
    }

    public static String forUser(String role, String uid) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("Firebase UID is required.");
        return "presence/" + groupForRole(role) + "/" + uid;
    }
}
