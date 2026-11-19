package com.spindletrack.auth;

public interface FirebaseTokenVerifier {
    FirebaseIdentity verify(String idToken);

    default FirebaseIdentity verifyForInvitationInbox(String idToken) {
        return verify(idToken);
    }
}
