package com.spindletrack.auth;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/shops")
public class ShopRegistrationController {
    private final ShopRegistrationService registrations;
    private final RegistrationRateLimitService rateLimit;

    public ShopRegistrationController(ShopRegistrationService registrations, RegistrationRateLimitService rateLimit) {
        this.registrations = registrations;
        this.rateLimit = rateLimit;
    }

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ShopRegistrationResponse> register(@Valid @RequestPart("shop") ShopRegistrationRequest request,
            @RequestPart(value = "documents", required = false) List<MultipartFile> documents,
            @AuthenticationPrincipal FirebasePrincipal principal, HttpServletRequest servletRequest) {
        int attemptsToday = rateLimit.recordAttempt(servletRequest.getRemoteAddr());
        if (attemptsToday > 3) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "You have reached the daily shop-registration limit.");
        ShopRegistrationResponse response = registrations.register(principal.uid(), request, documents);
        return ResponseEntity.created(URI.create("/api/shops/mine/registration")).body(response);
    }

    @GetMapping("/mine/registration")
    @PreAuthorize("hasRole('OWNER')")
    public ShopRegistrationStatusResponse registrationStatus(@AuthenticationPrincipal FirebasePrincipal principal) {
        return registrations.findMine(principal.uid());
    }

    @PutMapping(value = "/mine/registration", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('OWNER')")
    public ShopRegistrationResponse resubmit(@Valid @RequestPart("shop") ShopRegistrationRequest request,
            @RequestPart(value = "documents", required = false) List<MultipartFile> documents,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return registrations.resubmit(principal.uid(), request, documents);
    }

    @DeleteMapping("/mine/registration/documents/{documentId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> deleteRejectedDocument(@PathVariable String documentId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        registrations.deleteRejectedDocument(principal.uid(), documentId);
        return ResponseEntity.noContent().build();
    }
}
