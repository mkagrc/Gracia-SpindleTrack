package com.spindletrack.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class FirebaseAdminTokenVerifier implements FirebaseTokenVerifier {
    private static final String APP_NAME = "spindletrack-auth";
    private final String serviceAccountPath;
    private volatile FirebaseAuth firebaseAuth;

    public FirebaseAdminTokenVerifier(
            @Value("${spindletrack.firebase.service-account-path:}") String serviceAccountPath) {
        this.serviceAccountPath = serviceAccountPath;
    }

    @Override
    public FirebaseIdentity verify(String idToken) {
        try {
            FirebaseToken decoded = firebaseAuth().verifyIdToken(idToken);
            return identityFromClaims(decoded.getUid(), decoded.getClaims());
        } catch (FirebaseAuthenticationException exception) {
            throw exception;
        } catch (FirebaseAuthException exception) {
            throw new FirebaseAuthenticationException(
                    HttpStatus.UNAUTHORIZED, "The Firebase ID token is invalid or expired.");
        }
    }

    static FirebaseIdentity identityFromClaims(String uid, Map<String, Object> claims) {
        String email = stringClaim(claims, "email");
        if (!Boolean.TRUE.equals(claims.get("email_verified"))) {
            throw new FirebaseAuthenticationException(
                    HttpStatus.UNAUTHORIZED, "Please verify your email address before signing in.");
        }
        if (email == null || email.isBlank()) {
            throw new FirebaseAuthenticationException(
                    HttpStatus.UNAUTHORIZED, "The Firebase account does not have a verified email address.");
        }
        return new FirebaseIdentity(uid, email.trim(), stringClaim(claims, "name"));
    }

    private FirebaseAuth firebaseAuth() {
        FirebaseAuth current = firebaseAuth;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (firebaseAuth != null) {
                return firebaseAuth;
            }
            if (serviceAccountPath == null || serviceAccountPath.isBlank()) {
                throw new FirebaseAuthenticationException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Firebase authentication is not configured on the server.");
            }
            try {
                FirebaseApp app = FirebaseApp.getApps().stream()
                        .filter(existing -> APP_NAME.equals(existing.getName()))
                        .findFirst()
                        .orElseGet(this::initializeFirebaseApp);
                firebaseAuth = FirebaseAuth.getInstance(app);
                return firebaseAuth;
            } catch (FirebaseAuthenticationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new FirebaseAuthenticationException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Firebase authentication could not be initialized.");
            }
        }
    }

    private FirebaseApp initializeFirebaseApp() {
        try (var input = Files.newInputStream(Path.of(serviceAccountPath))) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(input))
                    .build();
            return FirebaseApp.initializeApp(options, APP_NAME);
        } catch (IOException | RuntimeException exception) {
            throw new FirebaseAuthenticationException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Firebase authentication could not be initialized.");
        }
    }

    private static String stringClaim(Map<String, Object> claims, String claim) {
        Object value = claims.get(claim);
        return value instanceof String text ? text : null;
    }
}
