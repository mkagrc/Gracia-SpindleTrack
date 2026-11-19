package com.spindletrack.auth;

public record FirebaseIdentity(String uid, String email, String displayName, boolean emailVerified) {
    public FirebaseIdentity(String uid, String email, String displayName) {
        this(uid, email, displayName, true);
    }
}
