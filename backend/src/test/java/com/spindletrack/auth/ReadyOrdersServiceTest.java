package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ReadyOrdersServiceTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Timestamp tsNow() {
        return Timestamp.of(Date.from(Instant.now()));
    }

    private static Timestamp tsDaysAgo(long days) {
        return Timestamp.of(Date.from(Instant.now().minusSeconds(days * 86400L)));
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference shopsCol  = mock(CollectionReference.class);
        final CollectionReference ordersCol = mock(CollectionReference.class);
        final CollectionReference historyCol = mock(CollectionReference.class);
        final DocumentReference shopRef = mock(DocumentReference.class);
        final DocumentSnapshot shopDoc = mock(DocumentSnapshot.class);
        final Query ordersQuery   = mock(Query.class);
        final QuerySnapshot ordersRows = mock(QuerySnapshot.class);
        final Query historyQuery  = mock(Query.class);
        final QuerySnapshot historyRows = mock(QuerySnapshot.class);

        Fixture() throws Exception {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shops")).thenReturn(shopsCol);
            when(db.collection("orders")).thenReturn(ordersCol);
            when(db.collection("orderStageHistory")).thenReturn(historyCol);

            when(shopsCol.document("shop-A")).thenReturn(shopRef);
            when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shopDoc));
            when(shopDoc.exists()).thenReturn(true);
            when(shopDoc.getString("status")).thenReturn("ACTIVE");

            when(ordersCol.whereEqualTo("shopId", "shop-A")).thenReturn(ordersQuery);
            when(ordersQuery.get()).thenReturn(ApiFutures.immediateFuture(ordersRows));
            when(ordersRows.getDocuments()).thenReturn(List.of());

            when(historyCol.whereEqualTo("shopId", "shop-A")).thenReturn(historyQuery);
            when(historyQuery.get()).thenReturn(ApiFutures.immediateFuture(historyRows));
            when(historyRows.getDocuments()).thenReturn(List.of());
        }

        QueryDocumentSnapshot order(String id, String stage, String paymentStatus, Timestamp createdAt) {
            QueryDocumentSnapshot doc = mock(QueryDocumentSnapshot.class);
            doReturn(id).when(doc).getId();
            when(doc.getString("shopId")).thenReturn("shop-A");
            when(doc.getString("stage")).thenReturn(stage);
            when(doc.getString("paymentStatus")).thenReturn(paymentStatus);
            when(doc.getString("orderCode")).thenReturn("O-" + id);
            when(doc.getString("customerName")).thenReturn("Customer " + id);
            when(doc.get("totalPriceCentavos")).thenReturn(1000L);
            when(doc.get("createdAt")).thenReturn(createdAt);
            when(doc.getString("unit")).thenReturn("kg");
            when(doc.get("kilos")).thenReturn(new BigDecimal("2.00"));
            return doc;
        }

        QueryDocumentSnapshot foreignOrder(String id, String stage) {
            QueryDocumentSnapshot doc = order(id, stage, "UNPAID", tsNow());
            when(doc.getString("shopId")).thenReturn("shop-B");
            return doc;
        }

        QueryDocumentSnapshot historyEvent(String orderId, String stage, Timestamp ts) {
            QueryDocumentSnapshot doc = mock(QueryDocumentSnapshot.class);
            when(doc.getString("orderId")).thenReturn(orderId);
            when(doc.getString("stage")).thenReturn(stage);
            when(doc.get("changedAt")).thenReturn(ts);
            return doc;
        }

        FirebasePrincipal owner() {
            return new FirebasePrincipal("uid", "owner@e.com", "Owner", "u1", "OWNER", "shop-A", "ACTIVE");
        }
        FirebasePrincipal staff() {
            return new FirebasePrincipal("uid", "staff@e.com", "Staff", "u2", "STAFF", "shop-A", "ACTIVE");
        }
        FirebasePrincipal customer() {
            return new FirebasePrincipal("uid", "c@e.com", "C", "u3", "CUSTOMER", null, null);
        }
        FirebasePrincipal admin() {
            return new FirebasePrincipal("uid", "a@e.com", "A", "u4", "PLATFORM_ADMIN", null, null);
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void paidReadyOrderAppearsInUnclaimed() throws Exception {
        Fixture f = new Fixture();
        QueryDocumentSnapshot paid = f.order("ord-paid", "READY", "PAID", tsNow());
        when(f.ordersRows.getDocuments()).thenReturn(List.of(paid));

        OwnerOperationsService.ReadyAndClaimedResult result =
                new OwnerOperationsService(f.firebase).readyOrders(f.owner());

        assertEquals(1, result.unclaimed().size());
        assertEquals("ord-paid", result.unclaimed().get(0).id());
        assertEquals("PAID", result.unclaimed().get(0).paymentStatus());
        assertTrue(result.claimed().isEmpty());
    }

    @Test
    void unpaidReadyOrderAppearsInUnclaimed() throws Exception {
        Fixture f = new Fixture();
        QueryDocumentSnapshot unpaid = f.order("ord-unpaid", "READY", "UNPAID", tsNow());
        when(f.ordersRows.getDocuments()).thenReturn(List.of(unpaid));

        OwnerOperationsService.ReadyAndClaimedResult result =
                new OwnerOperationsService(f.firebase).readyOrders(f.owner());

        assertEquals(1, result.unclaimed().size());
        assertEquals("UNPAID", result.unclaimed().get(0).paymentStatus());
    }

    @Test
    void pendingReadyOrderAppearsInUnclaimed() throws Exception {
        Fixture f = new Fixture();
        QueryDocumentSnapshot pending = f.order("ord-pending", "READY", "PENDING", tsNow());
        when(f.ordersRows.getDocuments()).thenReturn(List.of(pending));

        OwnerOperationsService.ReadyAndClaimedResult result =
                new OwnerOperationsService(f.firebase).readyOrders(f.owner());

        assertEquals(1, result.unclaimed().size());
        assertEquals("PENDING", result.unclaimed().get(0).paymentStatus());
    }

    @Test
    void claimedOrderDoesNotAppearInUnclaimedButAppearsInClaimed() throws Exception {
        Fixture f = new Fixture();
        QueryDocumentSnapshot claimed = f.order("ord-claimed", "CLAIMED", "PAID", tsNow());
        QueryDocumentSnapshot histClaimed = f.historyEvent("ord-claimed", "CLAIMED", tsNow());
        when(f.ordersRows.getDocuments()).thenReturn(List.of(claimed));
        when(f.historyRows.getDocuments()).thenReturn(List.of(histClaimed));

        OwnerOperationsService.ReadyAndClaimedResult result =
                new OwnerOperationsService(f.firebase).readyOrders(f.owner());

        assertTrue(result.unclaimed().isEmpty());
        assertEquals(1, result.claimed().size());
        assertEquals("ord-claimed", result.claimed().get(0).id());
    }

    @Test
    void foreignShopOrderNeverAppears() throws Exception {
        Fixture f = new Fixture();
        QueryDocumentSnapshot own     = f.order("ord-own", "READY", "UNPAID", tsNow());
        QueryDocumentSnapshot foreign = f.foreignOrder("ord-foreign", "READY");
        when(f.ordersRows.getDocuments()).thenReturn(List.of(own, foreign));

        OwnerOperationsService.ReadyAndClaimedResult result =
                new OwnerOperationsService(f.firebase).readyOrders(f.owner());

        assertEquals(1, result.unclaimed().size());
        assertEquals("ord-own", result.unclaimed().get(0).id());
    }

    @Test
    void overdueFlagIsTrueAtThreeDaysAndFalseAtTwoDaysTwentyThreeHours() throws Exception {
        Fixture f = new Fixture();
        Timestamp exactly3Days = tsDaysAgo(3);
        Timestamp lessThan3Days = Timestamp.of(Date.from(Instant.now().minusSeconds(3L * 86400 - 1)));
        QueryDocumentSnapshot overdue = f.order("ord-overdue", "READY", "UNPAID", exactly3Days);
        QueryDocumentSnapshot notOverdue = f.order("ord-recent", "READY", "UNPAID", lessThan3Days);
        QueryDocumentSnapshot histOverdue  = f.historyEvent("ord-overdue", "READY", exactly3Days);
        QueryDocumentSnapshot histRecent   = f.historyEvent("ord-recent",  "READY", lessThan3Days);
        when(f.ordersRows.getDocuments()).thenReturn(List.of(overdue, notOverdue));
        when(f.historyRows.getDocuments()).thenReturn(List.of(histOverdue, histRecent));

        OwnerOperationsService.ReadyAndClaimedResult result =
                new OwnerOperationsService(f.firebase).readyOrders(f.owner());

        OwnerOperationsService.ReadyOrder overdueRow  = result.unclaimed().stream().filter(r -> "ord-overdue".equals(r.id())).findFirst().orElseThrow();
        OwnerOperationsService.ReadyOrder recentRow   = result.unclaimed().stream().filter(r -> "ord-recent".equals(r.id())).findFirst().orElseThrow();
        assertTrue(overdueRow.overdue(),  "Order ready exactly 3 days ago must be overdue");
        assertFalse(recentRow.overdue(), "Order ready less than 3 days ago must not be overdue");
    }

    @Test
    void unclaimedSortsByOldestReadyFirst() throws Exception {
        Fixture f = new Fixture();
        Timestamp older = tsDaysAgo(5);
        Timestamp newer = tsDaysAgo(1);
        QueryDocumentSnapshot o1 = f.order("ord-old", "READY", "UNPAID", older);
        QueryDocumentSnapshot o2 = f.order("ord-new", "READY", "UNPAID", newer);
        QueryDocumentSnapshot h1 = f.historyEvent("ord-old", "READY", older);
        QueryDocumentSnapshot h2 = f.historyEvent("ord-new", "READY", newer);
        when(f.ordersRows.getDocuments()).thenReturn(List.of(o2, o1)); // new first in DB
        when(f.historyRows.getDocuments()).thenReturn(List.of(h1, h2));

        OwnerOperationsService.ReadyAndClaimedResult result =
                new OwnerOperationsService(f.firebase).readyOrders(f.owner());

        assertEquals("ord-old", result.unclaimed().get(0).id(), "Oldest ready order must come first");
        assertEquals("ord-new", result.unclaimed().get(1).id());
    }

    @Test
    void staffCanReadBothListsButCustomerAndAdminCannot() throws Exception {
        Fixture f = new Fixture();
        OwnerOperationsService svc = new OwnerOperationsService(f.firebase);

        // Staff allowed
        OwnerOperationsService.ReadyAndClaimedResult result = svc.readyOrders(f.staff());
        assertFalse(result == null);

        // Customer and admin forbidden
        ResponseStatusException customerErr = assertThrows(ResponseStatusException.class,
                () -> svc.readyOrders(f.customer()));
        assertEquals(HttpStatus.FORBIDDEN, customerErr.getStatusCode());

        ResponseStatusException adminErr = assertThrows(ResponseStatusException.class,
                () -> svc.readyOrders(f.admin()));
        assertEquals(HttpStatus.FORBIDDEN, adminErr.getStatusCode());
    }

    @Test
    void readyAtTakenFromHistoryNotCreatedAt() throws Exception {
        Fixture f = new Fixture();
        Timestamp created = tsDaysAgo(10);
        Timestamp readyHistory = tsDaysAgo(2);
        QueryDocumentSnapshot order = f.order("ord-hist", "READY", "UNPAID", created);
        QueryDocumentSnapshot hist = f.historyEvent("ord-hist", "READY", readyHistory);
        when(f.ordersRows.getDocuments()).thenReturn(List.of(order));
        when(f.historyRows.getDocuments()).thenReturn(List.of(hist));

        OwnerOperationsService.ReadyAndClaimedResult result =
                new OwnerOperationsService(f.firebase).readyOrders(f.owner());

        OwnerOperationsService.ReadyOrder row = result.unclaimed().get(0);
        // readyAt must come from history (2 days ago), not createdAt (10 days ago)
        assertFalse(row.overdue(), "readyAt from history 2 days ago must not be overdue");
    }
}
