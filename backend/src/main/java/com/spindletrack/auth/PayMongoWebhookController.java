package com.spindletrack.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/paymongo")
public class PayMongoWebhookController {
    private final PayMongoWebhookService webhooks;

    public PayMongoWebhookController(PayMongoWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/webhook")
    public ResponseEntity<PayMongoWebhookService.Receipt> receive(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "Paymongo-Signature", required = false) String signature) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(webhooks.receive(rawBody, signature));
    }
}
