package com.spindletrack.auth;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final UserAccountRepository users;
    private final String adminEmail;

    public AuthService(UserAccountRepository users, @Value("${spindletrack.auth.admin-email:}") String adminEmail) {
        this.users = users;
        this.adminEmail = normalizeEmail(adminEmail);
    }

    public AuthSessionResponse createOrLoadSession(FirebaseIdentity identity) {
        if (identity == null || !identity.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Please verify your email before signing in.");
        }
        boolean isConfiguredAdmin = isAdminEmail(identity.email());
        UserAccount account = users.findByFirebaseUid(identity.uid()).orElse(null);

        if (account == null) {
            UserAccount emailMatch = users.findByEmail(identity.email()).orElse(null);
            if (emailMatch != null) {
                if (!isConfiguredAdmin || (emailMatch.firebaseUid() != null && !emailMatch.firebaseUid().equals(identity.uid()))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Please use another account.");
                }
                if (!emailMatch.active()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is inactive.");
                users.bindAndPromotePlatformAdmin(emailMatch.id(), identity.uid(), displayName(identity), identity.email());
                account = users.findByFirebaseUid(identity.uid()).orElseThrow();
            } else {
                // Step 5 TODO: staff accounts must only be created by a verified owner invitation; never accept a client role.
                String assignedRole = isConfiguredAdmin ? "PLATFORM_ADMIN" : "CUSTOMER";
                try {
                    users.insertFirebaseUser(identity.uid(), displayName(identity), identity.email(), assignedRole);
                } catch (ResponseStatusException conflict) {
                    // A concurrent sign-in for this UID may have committed while this transaction retried.
                    UserAccount concurrent = users.findByFirebaseUid(identity.uid()).orElse(null);
                    if (concurrent == null) throw conflict;
                }
                account = users.findByFirebaseUid(identity.uid()).orElseThrow();
            }
        } else {
            if (!account.active()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is inactive.");
            if (isConfiguredAdmin) {
                users.promotePlatformAdmin(account.id());
            } else if (!isConfiguredAdmin && "PLATFORM_ADMIN".equals(account.role())) {
                users.demotePlatformAdmin(account.id());
            }
            users.updateIdentity(account.id(), identity.uid(), displayName(identity), identity.email());
            account = users.findByFirebaseUid(identity.uid()).orElseThrow();
        }

        AuthSessionResponse.ShopInfo shop = null;
        if (account.shopId() != null && ("OWNER".equals(account.role()) || "STAFF".equals(account.role()))) {
            shop = new AuthSessionResponse.ShopInfo(account.shopId(), users.findShopName(account.shopId()).orElse(""), account.shopStatus());
        }
        // RTDB rules trust only claims minted here after resolving the authoritative Firestore account.
        users.setSessionClaims(identity.uid(), account.role(), account.shopId());
        return new AuthSessionResponse(account.id(), account.fullName(), account.email(), account.role(),
                account.shopId(), account.shopStatus(), shop);
    }

    private boolean isAdminEmail(String email) { return !adminEmail.isBlank() && adminEmail.equals(normalizeEmail(email)); }

    private static String displayName(FirebaseIdentity identity) {
        String name = identity.displayName();
        if (name == null || name.isBlank()) name = identity.email().split("@", 2)[0];
        return name.length() <= 160 ? name : name.substring(0, 160);
    }

    private static String normalizeEmail(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
