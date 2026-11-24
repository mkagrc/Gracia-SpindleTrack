package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;
import com.spindletrack.LaundryStage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CustomerLinkedOrderServiceTest {
    @Test
    void staffCanResolveOnlyVerifiedExactEmailAndResponseContainsOnlyCustomerName() throws Exception {
        Fixture f = new Fixture();
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.findVerifiedCustomerByEmail("customer@example.com")).thenReturn(Optional.of(f.customer()));
        ShopOrderService service = new ShopOrderService(f.firebase, null, users);

        ShopOrderService.CustomerMatch result = service.findCustomerForOrder(f.staff(), " Customer@Example.com ");

        assertEquals("Verified Customer", result.name());
        verify(users).findVerifiedCustomerByEmail("customer@example.com");
    }

    @Test
    void nonmatchingPartialUnverifiedAndNonCustomerAccountsCannotBeLinked() throws Exception {
        Fixture f = new Fixture();
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.findVerifiedCustomerByEmail(any())).thenReturn(Optional.empty());
        ShopOrderService service = new ShopOrderService(f.firebase, null, users);
        for (String email : List.of("missing@example.com", "customer", "unverified@example.com", "staff@example.com")) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> service.findCustomerForOrder(f.staff(), email));
            assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        }
    }

    @Test
    void createLinksExactVerifiedCustomerAndUsesAuthoritativeCustomerNameInOneTransaction() throws Exception {
        Fixture f = new Fixture();
        f.prepareCreate();
        f.transactionHarness();
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.findVerifiedCustomerByEmail("customer@example.com")).thenReturn(Optional.of(f.customer()));
        ShopOrderService service = new ShopOrderService(f.firebase, null, users);

        ShopOrderService.OrderSummary created = service.create(f.staff(), new ShopOrderService.CreateOrderRequest(
                "Staff-entered name is ignored", "639171234567", "service-A", new BigDecimal("2.00"),
                new BigDecimal("50.00"), "", " Customer@Example.com "));

        assertEquals("customer-user-1", created.customerUserId());
        assertEquals("Verified Customer", created.customerName());
        verify(f.tx).create(eq(f.order), argThat(values -> "customer-user-1".equals(values.get("customerUserId"))
                && "Verified Customer".equals(values.get("customerName"))));
        verify(f.tx).get(f.customerRef);
    }

    @Test
    void customerListFiltersByUserIdAndForeignOrderDetailReturnsNotFound() throws Exception {
        Fixture f = new Fixture();
        CollectionReference orders = mock(CollectionReference.class);
        CollectionReference shops = mock(CollectionReference.class);
        Query query = mock(Query.class);
        QuerySnapshot rows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot ownOrder = mock(QueryDocumentSnapshot.class);
        DocumentSnapshot shop = mock(DocumentSnapshot.class);
        DocumentReference shopRef = mock(DocumentReference.class);
        when(f.db.collection("orders")).thenReturn(orders);
        when(orders.whereEqualTo("customerUserId", "customer-user-1")).thenReturn(query);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(rows));
        when(rows.getDocuments()).thenReturn(List.of(ownOrder));
        when(ownOrder.getId()).thenReturn("order-string-1");
        when(ownOrder.getString("shopId")).thenReturn("shop-string-1");
        when(ownOrder.getString("orderCode")).thenReturn("O-000001");
        when(ownOrder.getString("serviceName")).thenReturn("Wash");
        when(ownOrder.getString("stage")).thenReturn("RECEIVED");
        when(ownOrder.get("kilos")).thenReturn(new BigDecimal("2.00"));
        when(ownOrder.get("totalPrice")).thenReturn(new BigDecimal("50.00"));
        when(f.db.collection("shops")).thenReturn(shops);
        when(shops.document("shop-string-1")).thenReturn(shopRef);
        when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
        when(shop.exists()).thenReturn(true);
        when(shop.getString("name")).thenReturn("Laundry One");
        ShopOrderService service = new ShopOrderService(f.firebase);

        ShopOrderService.CustomerOrdersPage page = service.listMyOrders(f.customerPrincipal(), 0);

        assertEquals(1, page.totalElements());
        assertEquals("Laundry One", page.content().get(0).shopName());
        assertEquals(LaundryStage.RECEIVED, page.content().get(0).stage());
        verify(orders).whereEqualTo("customerUserId", "customer-user-1");

        DocumentReference foreignRef = mock(DocumentReference.class);
        DocumentSnapshot foreignOrder = mock(DocumentSnapshot.class);
        when(orders.document("foreign-order-id")).thenReturn(foreignRef);
        when(foreignRef.get()).thenReturn(ApiFutures.immediateFuture(foreignOrder));
        when(foreignOrder.exists()).thenReturn(true);
        when(foreignOrder.getString("customerUserId")).thenReturn("different-customer");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.getMyOrder(f.customerPrincipal(), "foreign-order-id"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(f.db, never()).collection("orderStageHistory");
    }

    private static final class Fixture {
        final FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        final Firestore db = mock(Firestore.class);
        final CollectionReference shops = mock(CollectionReference.class);
        final CollectionReference services = mock(CollectionReference.class);
        final CollectionReference counters = mock(CollectionReference.class);
        final CollectionReference orders = mock(CollectionReference.class);
        final CollectionReference histories = mock(CollectionReference.class);
        final CollectionReference users = mock(CollectionReference.class);
        final CollectionReference priceLogCollection = mock(CollectionReference.class);
        final DocumentReference shopRef = mock(DocumentReference.class);
        final DocumentReference serviceRef = mock(DocumentReference.class);
        final DocumentReference counterRef = mock(DocumentReference.class);
        final DocumentReference order = mock(DocumentReference.class);
        final DocumentReference history = mock(DocumentReference.class);
        final DocumentReference priceLogCollectionDoc = mock(DocumentReference.class);
        final DocumentReference customerRef = mock(DocumentReference.class);
        final DocumentSnapshot shop = mock(DocumentSnapshot.class);
        final DocumentSnapshot service = mock(DocumentSnapshot.class);
        final DocumentSnapshot counter = mock(DocumentSnapshot.class);
        final DocumentSnapshot customerSnapshot = mock(DocumentSnapshot.class);
        final Transaction tx = mock(Transaction.class);

        Fixture() throws Exception {
            when(firebase.firestore()).thenReturn(db);
            when(db.collection("shops")).thenReturn(shops);
            when(db.collection("services")).thenReturn(services);
            when(db.collection("orderCounters")).thenReturn(counters);
            when(db.collection("orders")).thenReturn(orders);
            when(db.collection("orderStageHistory")).thenReturn(histories);
            when(db.collection("users")).thenReturn(users);
            when(shops.document("shop-A")).thenReturn(shopRef);
            when(services.document("service-A")).thenReturn(serviceRef);
            when(counters.document("shop-A")).thenReturn(counterRef);
            when(orders.document()).thenReturn(order);
            when(order.getId()).thenReturn("order-string-new");
            when(order.collection("priceLog")).thenReturn(priceLogCollection);
            when(priceLogCollection.document()).thenReturn(priceLogCollectionDoc);
            when(histories.document()).thenReturn(history);
            when(users.document("customer-user-1")).thenReturn(customerRef);
            when(shopRef.get()).thenReturn(ApiFutures.immediateFuture(shop));
            when(shop.exists()).thenReturn(true);
            when(shop.getString("status")).thenReturn("ACTIVE");
        }

        void prepareCreate() throws Exception {
            when(counter.exists()).thenReturn(false);
            when(service.exists()).thenReturn(true);
            when(service.getString("shopId")).thenReturn("shop-A");
            when(service.getString("name")).thenReturn("Wash");
            when(service.getString("unit")).thenReturn("kg");
            when(service.getBoolean("active")).thenReturn(true);
            when(customerSnapshot.exists()).thenReturn(true);
            when(customerSnapshot.getBoolean("active")).thenReturn(true);
            when(customerSnapshot.getBoolean("emailVerified")).thenReturn(true);
            when(customerSnapshot.getString("role")).thenReturn("CUSTOMER");
            when(customerSnapshot.getString("normalizedEmail")).thenReturn("customer@example.com");
            when(customerSnapshot.getString("displayName")).thenReturn("Verified Customer");
        }

        @SuppressWarnings("unchecked")
        void transactionHarness() throws Exception {
            when(tx.get(any(DocumentReference.class))).thenAnswer(invocation -> {
                DocumentReference ref = invocation.getArgument(0);
                if (ref == shopRef) return ApiFutures.immediateFuture(shop);
                if (ref == counterRef) return ApiFutures.immediateFuture(counter);
                if (ref == serviceRef) return ApiFutures.immediateFuture(service);
                if (ref == customerRef) return ApiFutures.immediateFuture(customerSnapshot);
                return ApiFutures.immediateFuture(mock(DocumentSnapshot.class));
            });
            when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
                Transaction.Function<Object> work = invocation.getArgument(0);
                try { return ApiFutures.immediateFuture(work.updateCallback(tx)); }
                catch (Exception exception) { return ApiFutures.immediateFailedFuture(exception); }
            });
        }

        UserAccount customer() { return new UserAccount("customer-user-1", "customer-firebase-1", "Verified Customer", "customer@example.com", "CUSTOMER", true, null, null); }
        FirebasePrincipal staff() { return new FirebasePrincipal("staff-uid", "staff@example.com", "Shop Staff", "staff-user-1", "STAFF", "shop-A", "ACTIVE"); }
        FirebasePrincipal customerPrincipal() { return new FirebasePrincipal("customer-firebase-1", "customer@example.com", "Verified Customer", "customer-user-1", "CUSTOMER", null, null); }
    }
}
