package com.spindletrack.auth;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OwnerShopService {
    private final JdbcTemplate jdbc;
    public OwnerShopService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ShopProfile(long id, String name, String address, String contactNumber, String status) {}
    public record UpdateRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=160) String name,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String address,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min=7,max=32) String contactNumber) {}

    @Transactional(readOnly = true)
    public ShopProfile getMine(Long shopId) {
        long id = requireShopId(shopId);
        List<ShopProfile> rows = jdbc.query("SELECT id,name,address,contact_number,status FROM shops WHERE id=?",
                (rs, n) -> new ShopProfile(rs.getLong("id"),rs.getString("name"),rs.getString("address"),rs.getString("contact_number"),rs.getString("status")), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        requireActive(rows.getFirst().status());
        return rows.getFirst();
    }

    @Transactional
    public ShopProfile updateMine(Long shopId, UpdateRequest request) {
        long id = requireShopId(shopId);
        List<String> statuses = jdbc.query("SELECT status FROM shops WHERE id=? FOR UPDATE", (rs,n) -> rs.getString(1), id);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        requireActive(statuses.getFirst());
        jdbc.update("UPDATE shops SET name=?,address=?,contact_number=? WHERE id=?", request.name().trim(), request.address().trim(), request.contactNumber().trim(), id);
        return getMine(id);
    }

    private static long requireShopId(Long shopId) {
        if (shopId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This owner account is not linked to a shop.");
        return shopId;
    }
    private static void requireActive(String status) {
        if (!"ACTIVE".equals(status)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Shop profile editing is available only while the shop is ACTIVE.");
    }
}
