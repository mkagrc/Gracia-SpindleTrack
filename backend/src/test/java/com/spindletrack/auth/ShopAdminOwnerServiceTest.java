package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ShopAdminOwnerServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock ShopDocumentRepository documents;

    @Test
    void rejectFromPendingStoresReasonAndReviewAudit() {
        when(jdbc.query(eq("SELECT status FROM shops WHERE id=? FOR UPDATE"), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(2L)))
                .thenReturn(List.of("PENDING"));
        new AdminShopService(jdbc, documents).transition(2L, "REJECTED", "Missing permit", 99L);
        verify(jdbc).update("UPDATE shops SET status=?, rejection_reason=?, reviewed_by=?, reviewed_at=CURRENT_TIMESTAMP WHERE id=?",
                "REJECTED", "Missing permit", 99L, 2L);
        verify(jdbc).update("INSERT INTO shop_review_history (shop_id, action, reason, acted_by) VALUES (?, ?, ?, ?)",
                2L, "REJECTED", "Missing permit", 99L);
    }

    @Test
    void invalidTransitionReturnsConflictWithoutMutation() {
        when(jdbc.query(eq("SELECT status FROM shops WHERE id=? FOR UPDATE"), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(2L)))
                .thenReturn(List.of("ACTIVE"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new AdminShopService(jdbc, documents).transition(2L, "APPROVED", null, 99L));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("Cannot approved a shop with status ACTIVE.", error.getReason());
    }

    @Test
    void ownerCannotReadAnOtherShopAndNonActiveShopCannotBeEdited() {
        OwnerShopService service = new OwnerShopService(jdbc);
        ResponseStatusException unlinked = assertThrows(ResponseStatusException.class, () -> service.getMine(null));
        assertEquals(HttpStatus.FORBIDDEN, unlinked.getStatusCode());
        when(jdbc.query(eq("SELECT id,name,address,contact_number,status FROM shops WHERE id=?"), org.mockito.ArgumentMatchers.<RowMapper<OwnerShopService.ShopProfile>>any(), eq(44L)))
                .thenReturn(List.of(new OwnerShopService.ShopProfile(44L, "Mine", "Address", "1234567", "PENDING")));
        ResponseStatusException pending = assertThrows(ResponseStatusException.class, () -> service.getMine(44L));
        assertEquals(HttpStatus.FORBIDDEN, pending.getStatusCode());
    }
}
