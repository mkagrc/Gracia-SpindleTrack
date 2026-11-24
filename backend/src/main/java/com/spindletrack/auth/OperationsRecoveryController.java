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
    private final PayMongoWebhookService webhooks;

    public OperationsRecoveryController(OrderNotificationDispatcher notifications, PayMongoWebhookService webhooks) {
        this.notifications = notifications;
        this.webhooks = webhooks;
    }

    @PostMapping("/email-outbox/{eventId}/retry")
    public ResponseEntity<Void> retryEmail(@PathVariable String eventId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        notifications.retryDeadLetter(principal, eventId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/paymongo-webhooks/{eventId}/retry")
    public ResponseEntity<Void> retryPaymentWebhook(@PathVariable String eventId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        webhooks.retryDeadLetter(principal, eventId);
        return ResponseEntity.noContent().build();
    }
}
