package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class UserPresenceServiceTest {
    @Test
    void ownerStaffPresenceIsFilteredByTheAuthenticatedShopAndOmitsEmail() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        PresenceStore presence = mock(PresenceStore.class);
        Firestore db = mock(Firestore.class);
        CollectionReference users = mock(CollectionReference.class);
        Query scopedQuery = mock(Query.class);
        QuerySnapshot snapshot = mock(QuerySnapshot.class);
        QueryDocumentSnapshot staff = mock(QueryDocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("users")).thenReturn(users);
        when(users.whereEqualTo("shopId", "shop-A")).thenReturn(scopedQuery);
        when(scopedQuery.whereEqualTo("role", "STAFF")).thenReturn(scopedQuery);
        when(scopedQuery.get()).thenReturn(ApiFutures.immediateFuture(snapshot));
        when(snapshot.getDocuments()).thenReturn(List.of(staff));
        when(staff.getString("firebaseUid")).thenReturn("staff-uid");
        when(staff.getString("displayName")).thenReturn("Shop Staff");
        when(presence.readGroup("staff")).thenReturn(Map.of("staff-uid", new PresenceStore.Entry(true, 1234L, "shop-A"),
                "other-shop-staff", new PresenceStore.Entry(true, 5678L, "shop-B")));

        List<UserPresenceService.StaffPresence> result = new UserPresenceService(firebase, presence).staffForShop("shop-A");

        assertEquals(1, result.size());
        assertEquals("Shop Staff", result.getFirst().name());
        assertEquals(true, result.getFirst().online());
        assertEquals(1234L, result.getFirst().lastSeen());
        assertFalse(java.util.Arrays.stream(result.getFirst().getClass().getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase().contains("email")));
        verify(users).whereEqualTo("shopId", "shop-A");
        verify(scopedQuery).whereEqualTo("role", "STAFF");
    }

    @Test
    void ownerWithoutShopCannotReadAnyStaffPresence() {
        UserPresenceService service = new UserPresenceService(mock(FirebaseAdminTokenVerifier.class), mock(PresenceStore.class));
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.staffForShop(null));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void adminDirectoryOmitsCustomerEmailButReturnsNameAndPresence() throws Exception {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        PresenceStore presence = mock(PresenceStore.class);
        Firestore db = mock(Firestore.class);
        CollectionReference users = mock(CollectionReference.class);
        QuerySnapshot snapshot = mock(QuerySnapshot.class);
        QueryDocumentSnapshot customer = mock(QueryDocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("users")).thenReturn(users);
        when(users.get()).thenReturn(ApiFutures.immediateFuture(snapshot));
        when(snapshot.getDocuments()).thenReturn(List.of(customer));
        when(customer.getString("role")).thenReturn("CUSTOMER");
        when(customer.getString("email")).thenReturn("private@example.com");
        when(customer.getString("displayName")).thenReturn("Customer Name");
        when(customer.getString("firebaseUid")).thenReturn("customer-uid");
        when(presence.readGroup("customers")).thenReturn(Map.of("customer-uid", new PresenceStore.Entry(true, 1234L, null)));

        UserPresenceService.AdminUser result = new UserPresenceService(firebase, presence).listUsers(null, 0).content().getFirst();

        assertNull(result.email());
        assertEquals("Customer Name", result.name());
        assertEquals("CUSTOMER", result.role());
        assertEquals(true, result.online());
        assertEquals(1234L, result.lastSeen());
    }
}
