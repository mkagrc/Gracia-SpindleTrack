package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;

class AdminShopOrderCountTest {
    @Test
    void listReturnsPerShopOrderCountWithoutReturningOrderData() {
        FirebaseAdminTokenVerifier firebase = mock(FirebaseAdminTokenVerifier.class);
        Firestore db = mock(Firestore.class);
        CollectionReference shops = mock(CollectionReference.class), users = mock(CollectionReference.class), orders = mock(CollectionReference.class);
        QuerySnapshot shopRows = mock(QuerySnapshot.class), userRows = mock(QuerySnapshot.class), orderRows = mock(QuerySnapshot.class);
        QueryDocumentSnapshot shop = mock(QueryDocumentSnapshot.class), order1 = mock(QueryDocumentSnapshot.class), order2 = mock(QueryDocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("shops")).thenReturn(shops); when(db.collection("users")).thenReturn(users); when(db.collection("orders")).thenReturn(orders);
        when(shops.get()).thenReturn(ApiFutures.immediateFuture(shopRows)); when(shopRows.getDocuments()).thenReturn(List.of(shop));
        when(users.get()).thenReturn(ApiFutures.immediateFuture(userRows)); when(userRows.getDocuments()).thenReturn(List.of());
        when(shop.getId()).thenReturn("shop-A"); when(shop.getString("name")).thenReturn("A"); when(shop.getString("status")).thenReturn("ACTIVE");
        Query scoped = mock(Query.class); when(orders.whereEqualTo("shopId", "shop-A")).thenReturn(scoped);
        when(scoped.get()).thenReturn(ApiFutures.immediateFuture(orderRows)); when(orderRows.getDocuments()).thenReturn(List.of(order1, order2));

        AdminShopService.PageResult page = new AdminShopService(firebase, mock(ShopDocumentRepository.class)).list(null, null, 0);
        assertEquals(1, page.content().size());
        assertEquals(2, page.content().get(0).orderCount());
    }
}
