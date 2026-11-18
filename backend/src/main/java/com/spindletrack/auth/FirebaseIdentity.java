package com.spindletrack.auth;

public record FirebaseIdentity(String uid, String email, String displayName) {
}
