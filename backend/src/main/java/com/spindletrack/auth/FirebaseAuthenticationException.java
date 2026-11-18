package com.spindletrack.auth;

import org.springframework.http.HttpStatus;

public class FirebaseAuthenticationException extends RuntimeException {
    private final HttpStatus status;

    public FirebaseAuthenticationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public FirebaseAuthenticationException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
