package com.spindletrack.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.cloud.FirestoreClient;
import com.google.firebase.database.FirebaseDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class FirebaseAdminTokenVerifier implements FirebaseTokenVerifier {
    private static final System.Logger LOGGER = System.getLogger(FirebaseAdminTokenVerifier.class.getName());
    private static final Map<String, String> PRESENCE_GROUPS = Map.of(
            "PLATFORM_ADMIN", "admin", "OWNER", "owners", "STAFF", "staff", "CUSTOMER", "customers");
    private static final String APP_NAME = "spindletrack-auth";
    private final String serviceAccountPath;
    private final String databaseUrl;
    private volatile FirebaseApp firebaseApp;
    private volatile FirebaseAuth firebaseAuth;
    private volatile FirebaseDatabase firebaseDatabase;

    public FirebaseAdminTokenVerifier(
            @Value("${spindletrack.firebase.service-account-path:}") String serviceAccountPath,
            @Value("${spindletrack.firebase.database-url:}") String databaseUrl) {
        this.serviceAccountPath = serviceAccountPath;
        this.databaseUrl = databaseUrl == null ? "" : databaseUrl.trim();
    }

    @Override
    public FirebaseIdentity verify(String idToken) {
        try {
            FirebaseToken decoded = firebaseAuth().verifyIdToken(idToken);
            return identityFromClaims(decoded.getUid(), decoded.getClaims());
        } catch (FirebaseAuthenticationException exception) {
            throw exception;
        } catch (FirebaseAuthException exception) {
            throw new FirebaseAuthenticationException(HttpStatus.UNAUTHORIZED, "The Firebase ID token is invalid or expired.");
        }
    }

    @Override
    public FirebaseIdentity verifyForInvitationInbox(String idToken) {
        try {
            FirebaseToken decoded = firebaseAuth().verifyIdToken(idToken);
            return identityFromClaimsForInvitationInbox(decoded.getUid(), decoded.getClaims());
        } catch (FirebaseAuthenticationException exception) {
            throw exception;
        } catch (FirebaseAuthException exception) {
            throw new FirebaseAuthenticationException(HttpStatus.UNAUTHORIZED, "The Firebase ID token is invalid or expired.");
        }
    }

    static FirebaseIdentity identityFromClaims(String uid, Map<String, Object> claims) {
        FirebaseIdentity identity = identityFromClaimsForInvitationInbox(uid, claims);
        if (!identity.emailVerified()) {
            throw new FirebaseAuthenticationException(HttpStatus.UNAUTHORIZED, "Please verify your email address before signing in.");
        }
        return identity;
    }

    static FirebaseIdentity identityFromClaimsForInvitationInbox(String uid, Map<String, Object> claims) {
        String email = stringClaim(claims, "email");
        if (email == null || email.isBlank()) {
            throw new FirebaseAuthenticationException(HttpStatus.UNAUTHORIZED, "The Firebase account does not have an email address.");
        }
        return new FirebaseIdentity(uid, email.trim(), stringClaim(claims, "name"), Boolean.TRUE.equals(claims.get("email_verified")));
    }

    Firestore firestore() { return FirestoreClient.getFirestore(firebaseApp()); }
    FirebaseDatabase realtimeDatabase() {
        if (databaseUrl.isBlank()) throw new FirebaseAuthenticationException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase Realtime Database is not configured. Set FIREBASE_DATABASE_URL.");
        FirebaseDatabase current = firebaseDatabase;
        if (current != null) return current;
        synchronized (this) {
            if (firebaseDatabase == null) firebaseDatabase = FirebaseDatabase.getInstance(firebaseApp(), databaseUrl);
            return firebaseDatabase;
        }
    }

    void setSessionClaims(String uid, String role, String shopId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        if (shopId != null && !shopId.isBlank()) claims.put("shopId", shopId);
        try {
            firebaseAuth().setCustomUserClaims(uid, claims);
        } catch (FirebaseAuthException exception) {
            throw new FirebaseAuthenticationException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase authorization claims could not be refreshed.");
        }
        removeOtherRolePresenceNodes(uid, role);
    }

    void removeOtherRolePresenceNodes(String uid, String role) {
        String currentGroup = PRESENCE_GROUPS.get(role);
        if (uid == null || uid.isBlank() || currentGroup == null) return;
        for (String group : PRESENCE_GROUPS.values()) {
            if (group.equals(currentGroup)) continue;
            try {
                realtimeDatabase().getReference("presence").child(group).child(uid).removeValueAsync().get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.log(System.Logger.Level.WARNING, "Could not remove stale presence node from group {0} (interrupted).", group);
            } catch (Exception exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not remove stale presence node from group {0} ({1}).",
                        group, exception.getClass().getSimpleName());
            }
        }
    }

    private FirebaseAuth firebaseAuth() {
        FirebaseAuth current = firebaseAuth;
        if (current != null) return current;
        synchronized (this) {
            if (firebaseAuth == null) firebaseAuth = FirebaseAuth.getInstance(firebaseApp());
            return firebaseAuth;
        }
    }

    private FirebaseApp firebaseApp() {
        FirebaseApp current = firebaseApp;
        if (current != null) return current;
        synchronized (this) {
            if (firebaseApp != null) return firebaseApp;
            if (serviceAccountPath == null || serviceAccountPath.isBlank()) {
                throw new FirebaseAuthenticationException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase authentication is not configured on the server.");
            }
            try {
                firebaseApp = FirebaseApp.getApps().stream().filter(existing -> APP_NAME.equals(existing.getName()))
                        .findFirst().orElseGet(this::initializeFirebaseApp);
                return firebaseApp;
            } catch (FirebaseAuthenticationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new FirebaseAuthenticationException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase authentication could not be initialized.");
            }
        }
    }

    private FirebaseApp initializeFirebaseApp() {
        try (var input = Files.newInputStream(Path.of(serviceAccountPath))) {
            FirebaseOptions.Builder options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(input));
            if (!databaseUrl.isBlank()) options.setDatabaseUrl(databaseUrl);
            return FirebaseApp.initializeApp(options.build(), APP_NAME);
        } catch (IOException | RuntimeException exception) {
            throw new FirebaseAuthenticationException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase authentication could not be initialized.");
        }
    }

    private static String stringClaim(Map<String, Object> claims, String claim) {
        Object value = claims.get(claim);
        return value instanceof String text ? text : null;
    }
}
