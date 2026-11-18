package com.spindletrack.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ShopDocumentAccessPolicy {
    public boolean canOpen(String role, String requesterId, String uploadedBy) {
        return "PLATFORM_ADMIN".equals(role)
                || ("OWNER".equals(role) && requesterId != null && requesterId.equals(uploadedBy));
    }

    public void requireCanOpen(String role, String requesterId, String uploadedBy) {
        if (!canOpen(role, requesterId, uploadedBy)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the platform administrator or the owner who uploaded this document can open it.");
        }
    }
}
