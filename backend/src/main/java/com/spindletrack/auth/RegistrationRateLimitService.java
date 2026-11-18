package com.spindletrack.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Service;

@Service
public class RegistrationRateLimitService {
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
