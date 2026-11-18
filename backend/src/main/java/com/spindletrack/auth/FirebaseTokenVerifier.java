package com.spindletrack.auth;

public interface FirebaseTokenVerifier {
    FirebaseIdentity verify(String idToken);
}
