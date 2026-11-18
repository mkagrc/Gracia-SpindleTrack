package com.spindletrack.auth;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;

final class FirestoreSupport {
    private FirestoreSupport() {}

    static <T> T transact(Firestore firestore, Function<Transaction, T> work) {
        try {
            return firestore.runTransaction(work::apply).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Firestore operation was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Firestore operation failed.", cause);
        }
    }

    static <T> T await(com.google.api.core.ApiFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Firestore operation was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Firestore operation failed.", cause);
        }
    }

    static LocalDateTime dateTime(Object value) {
        if (value instanceof Timestamp timestamp) return LocalDateTime.ofInstant(timestamp.toDate().toInstant(), ZoneOffset.UTC);
        if (value instanceof Date date) return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        if (value instanceof Instant instant) return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        return null;
    }

    static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.of(Date.from(value.toInstant(ZoneOffset.UTC)));
    }
}
