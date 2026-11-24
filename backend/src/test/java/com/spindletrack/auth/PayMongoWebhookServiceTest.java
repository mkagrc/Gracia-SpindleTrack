package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PayMongoWebhookServiceTest {
    private static final String SECRET = "whsec_test_secret";
    private static final long NOW = 1_800_000_000L;
    private static final byte[] BODY = ("{\"data\":{\"id\":\"evt_12345\",\"attributes\":{"+
            "\"type\":\"link.payment.paid\",\"livemode\":false,\"data\":{\"id\":\"link_abcde\"}}}}")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void validTestSignatureUsesExactRawBodyAndRejectsExpiredTimestamp() {
        PayMongoWebhookService service = service(mock(FirebaseAdminTokenVerifier.class), mock(ShopOrderService.class));
        assertTrue(service.verifySignature(BODY, signature(BODY, NOW), NOW));
        assertThrows(ResponseStatusException.class, () -> service.verifySignature(BODY, signature(BODY, NOW - 301), NOW));
        byte[] changedBody = (new String(BODY, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        assertThrows(ResponseStatusException.class, () -> service.verifySignature(changedBody, signature(BODY, NOW), NOW));
    }

    @Test
    void signedPaidEventIsPersistedOnceAndDuplicateIdIsAcknowledgedWithoutAnotherWrite() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference events = mock(CollectionReference.class);
        DocumentReference event = mock(DocumentReference.class);
        DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
        Transaction transaction = mock(Transaction.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("paymongoWebhookEvents")).thenReturn(events);
        when(events.document("evt_12345")).thenReturn(event);
        when(transaction.get(event)).thenReturn(ApiFutures.immediateFuture(snapshot));
        when(snapshot.exists()).thenReturn(false, true);
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> work = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(work.updateCallback(transaction)); }
            catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
        });
        PayMongoWebhookService service = service(firebase, mock(ShopOrderService.class));

        PayMongoWebhookService.Receipt first = service.receive(BODY, signature(BODY, NOW), NOW);
        PayMongoWebhookService.Receipt duplicate = service.receive(BODY, signature(BODY, NOW), NOW);

        assertEquals("evt_12345", first.eventId());
        assertFalse(first.duplicate());
        assertTrue(first.supportedEvent());
        assertTrue(duplicate.duplicate());
        verify(transaction, times(1)).create(eq(event), any());
    }

    @Test
    void unsupportedSignedEventIsAcknowledgedWithoutPaymentProcessing() {
        byte[] body = ("{\"data\":{\"id\":\"evt_98765\",\"attributes\":{"+
                "\"type\":\"some.other.event\",\"livemode\":false}}}").getBytes(StandardCharsets.UTF_8);
        PayMongoWebhookService service = service(mock(FirebaseAdminTokenVerifier.class), mock(ShopOrderService.class));
        PayMongoWebhookService.Receipt receipt = service.receive(body, signature(body, NOW), NOW);
        assertEquals("evt_98765", receipt.eventId());
        assertFalse(receipt.supportedEvent());
    }

    private static PayMongoWebhookService service(FirebaseAdminTokenVerifier firebase, ShopOrderService orders) {
        return new PayMongoWebhookService(firebase, orders, new ObjectMapper(), SECRET, "test");
    }

    private static String signature(byte[] body, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            byte[] digest = mac.doFinal(body);
            return "t=" + timestamp + ",te=" + HexFormat.of().formatHex(digest) + ",li=" + "0".repeat(64);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
}
