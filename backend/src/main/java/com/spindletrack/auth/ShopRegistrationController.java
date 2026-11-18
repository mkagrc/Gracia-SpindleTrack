package com.spindletrack.auth;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/shops")
public class ShopRegistrationController {
    private final ShopRegistrationService registrations;
    private final RegistrationRateLimitService rateLimit;

    public ShopRegistrationController(
            ShopRegistrationService registrations,
            RegistrationRateLimitService rateLimit) {
        this.registrations = registrations;
        this.rateLimit = rateLimit;
    }

    @PostMapping("/register")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ShopRegistrationResponse> register(
            @Valid @RequestBody ShopRegistrationRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal,
            HttpServletRequest servletRequest) {
        int attemptsToday = rateLimit.recordAttempt(servletRequest.getRemoteAddr());
        if (attemptsToday > 3) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "You have reached the daily shop-registration limit.");
        }
        ShopRegistrationResponse response = registrations.register(principal.uid(), request);
        return ResponseEntity.created(URI.create("/api/shops/mine/registration")).body(response);
    }

    @GetMapping("/mine/registration")
    @PreAuthorize("hasRole('OWNER')")
    public ShopRegistrationStatusResponse registrationStatus(
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return registrations.findMine(principal.uid());
    }
}
