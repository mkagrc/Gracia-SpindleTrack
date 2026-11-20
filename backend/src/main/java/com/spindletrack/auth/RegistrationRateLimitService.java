package com.spindletrack.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistrationRateLimitService {
    private static final long ORDER_LOOKUP_WINDOW_SECONDS = 15 * 60L;
    private static final long ORDER_LOOKUP_MAX_ATTEMPTS = 5L;
    private final FirebaseAdminTokenVerifier firebase;

    public RegistrationRateLimitService(FirebaseAdminTokenVerifier firebase) {
        this.firebase = firebase;
    }

    public int recordAttempt(String remoteAddress) {
        String ipHash = hashAddress(remoteAddress);
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        Firestore db = firebase.firestore();
        DocumentReference ref = db.collection("shopRegistrationRateLimits")
                .document(ipHash + "_" + todayUtc);
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(ref));
            long count = current.exists() && current.getLong("attemptCount") != null
                    ? current.getLong("attemptCount") + 1 : 1;
            tx.set(ref, Map.of("ipHash", ipHash, "attemptDate", todayUtc.toString(),
                    "attemptCount", count, "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return Math.toIntExact(count);
        });
    }

    public int recordPublicOrderLookupAttempt(String remoteAddress) {
        return recordPublicOrderLookupAttempt(remoteAddress, Instant.now());
    }

    int recordPublicOrderLookupAttempt(String remoteAddress, Instant now) {
        String ipHash = hashAddress(remoteAddress == null ? "unknown" : remoteAddress);
        long nowEpoch = now.getEpochSecond();
        Firestore db = firebase.firestore();
        DocumentReference ref = db.collection("shopRegistrationRateLimits").document("orderLookup_" + ipHash);
        return FirestoreSupport.transact(db, tx -> {
            DocumentSnapshot current = FirestoreSupport.await(tx.get(ref));
            Long previousStart = current.exists() ? current.getLong("windowStartEpoch") : null;
            Long previousCount = current.exists() ? current.getLong("attemptCount") : null;
            long windowStart = previousStart == null ? nowEpoch : previousStart;
            long count = previousCount == null ? 0L : previousCount;
            if (nowEpoch < windowStart || nowEpoch - windowStart >= ORDER_LOOKUP_WINDOW_SECONDS) {
                windowStart = nowEpoch;
                count = 0L;
            }
            if (count >= ORDER_LOOKUP_MAX_ATTEMPTS) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many order lookup attempts. Please wait 15 minutes and try again.");
            }
            long nextCount = count + 1L;
            tx.set(ref, Map.of("ipHash", ipHash, "windowStartEpoch", windowStart, "attemptCount", nextCount,
                    "updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp()));
            return Math.toIntExact(nextCount);
        });
    }

    static String hashAddress(String remoteAddress) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(remoteAddress.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
