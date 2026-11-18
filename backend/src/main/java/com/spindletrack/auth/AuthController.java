package com.spindletrack.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/session")
    public AuthSessionResponse createSession(@AuthenticationPrincipal FirebasePrincipal principal) {
        return authService.createOrLoadSession(
                new FirebaseIdentity(principal.uid(), principal.email(), principal.displayName()));
    }
}
