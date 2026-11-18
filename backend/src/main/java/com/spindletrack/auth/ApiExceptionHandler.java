package com.spindletrack.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException exception) {
        String message = exception.getReason() == null ? "Request could not be completed." : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Please check the submitted form.");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "message", "Uploads are limited to five JPG, PNG, or PDF documents, up to 5 MB each."));
    }

    @ExceptionHandler(FirebaseAuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleFirebase(FirebaseAuthenticationException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "The request could not be completed. Please try again."));
    }
}
