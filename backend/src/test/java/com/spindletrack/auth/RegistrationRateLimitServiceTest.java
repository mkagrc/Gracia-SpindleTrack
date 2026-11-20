package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RegistrationRateLimitServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final String ADDRESS = "198.51.100.9";

    @Test
    void remoteAddressesAreStoredAsStableSha256HexHashes() {
        String first = RegistrationRateLimitService.hashAddress("192.0.2.10");
        String second = RegistrationRateLimitService.hashAddress("192.0.2.10");
        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNotEquals("192.0.2.10", first);
    }

    @Test
    void fifthOrderLookupAttemptIsAllowedAndSixthIsRejectedWithoutPersistingRawAddress() throws Exception {
        Fixture f = new Fixture();
        when(f.snapshot.exists()).thenReturn(true);
        when(f.snapshot.getLong("windowStartEpoch")).thenReturn(NOW.minusSeconds(60).getEpochSecond());
        when(f.snapshot.getLong("attemptCount")).thenReturn(4L, 5L);
        RegistrationRateLimitService service = new RegistrationRateLimitService(f.firebase);

        assertEquals(5, service.recordPublicOrderLookupAttempt(ADDRESS, NOW));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.recordPublicOrderLookupAttempt(ADDRESS, NOW.plusSeconds(1)));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
        verify(f.collection, times(2)).document("orderLookup_" + RegistrationRateLimitService.hashAddress(ADDRESS));
        verify(f.tx).set(eq(f.ref), org.mockito.ArgumentMatchers.argThat(values ->
                values.get("attemptCount").equals(5L) && !values.containsValue(ADDRESS)));
    }

    @Test
    void windowResetsAtFifteenMinutes() throws Exception {
        Fixture f = new Fixture();
        when(f.snapshot.exists()).thenReturn(true);
        when(f.snapshot.getLong("windowStartEpoch")).thenReturn(NOW.minusSeconds(900).getEpochSecond());
        when(f.snapshot.getLong("attemptCount")).thenReturn(5L);

        assertEquals(1, new RegistrationRateLimitService(f.firebase).recordPublicOrderLookupAttempt(ADDRESS, NOW));
        verify(f.tx).set(eq(f.ref), org.mockito.ArgumentMatchers.argThat(values ->
                values.get("attemptCount").equals(1L) && values.get("windowStartEpoch").equals(NOW.getEpochSecond())));
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference collection = mock(CollectionReference.class);
        final DocumentReference ref = mock(DocumentReference.class);
        final DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
        final Transaction tx = mock(Transaction.class);

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shopRegistrationRateLimits")).thenReturn(collection);
            when(collection.document(anyString())).thenReturn(ref);
            when(tx.get(ref)).thenReturn(ApiFutures.immediateFuture(snapshot));
            when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
                Transaction.Function<Object> work = invocation.getArgument(0);
                try { return ApiFutures.immediateFuture(work.updateCallback(tx)); }
                catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
            });
        }
    }
}
