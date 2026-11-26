package com.spindletrack.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/operations")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class OperationsRecoveryController {
    private final OrderNotificationDispatcher notifications;

    public OperationsRecoveryController(OrderNotificationDispatcher notifications) {
        this.notifications = notifications;
    }

    @PostMapping("/email-outbox/{eventId}/retry")
    public ResponseEntity<Void> retryEmail(@PathVariable String eventId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        notifications.retryDeadLetter(principal, eventId);
        return ResponseEntity.noContent().build();
    }
}
