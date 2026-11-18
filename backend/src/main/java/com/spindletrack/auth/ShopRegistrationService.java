package com.spindletrack.auth;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopRegistrationService {
    private final JdbcTemplate jdbcTemplate;
    private final UserAccountRepository users;
    private final ShopRegistrationEligibility eligibility;

    public ShopRegistrationService(
            JdbcTemplate jdbcTemplate,
            UserAccountRepository users,
            ShopRegistrationEligibility eligibility) {
        this.jdbcTemplate = jdbcTemplate;
        this.users = users;
        this.eligibility = eligibility;
    }

    @Transactional
    public ShopRegistrationResponse register(String firebaseUid, ShopRegistrationRequest request) {
        UserAccount account = users.findByFirebaseUidForUpdate(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Sign in before registering your shop."));
        if (!account.active()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is inactive.");
        }
        eligibility.requireEligible(account, users.hasAttachedOrders(account.id()));

        String name = request.shopName().trim();
        String address = request.address().trim();
        String contactNumber = request.contactNumber().trim();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO shops (name, address, contact_number, status) VALUES (?, ?, ?, 'PENDING')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, address);
            statement.setString(3, contactNumber);
            return statement;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("Shop registration did not return an identifier.");
        }
        long shopId = generatedId.longValue();
        users.convertCustomerToOwner(account.id(), shopId);
        jdbcTemplate.update("""
                INSERT INTO shop_review_history (shop_id, action, acted_by)
                VALUES (?, 'SUBMITTED', ?)
                """, shopId, account.id());
        return new ShopRegistrationResponse(
                shopId, name, "PENDING", "Shop registration submitted. Waiting for approval.");
    }

    @Transactional(readOnly = true)
    public ShopRegistrationStatusResponse findMine(String firebaseUid) {
        List<ShopRegistrationStatusResponse> rows = jdbcTemplate.query("""
                SELECT s.id, s.name, s.address, s.contact_number, s.status,
                       s.rejection_reason, s.created_at
                FROM shops s
                JOIN users u ON u.shop_id = s.id
                WHERE u.firebase_uid = ? AND u.role = 'OWNER'
                ORDER BY s.id DESC
                LIMIT 1
                """, SHOP_STATUS_MAPPER, firebaseUid);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No shop registration was found.");
        }
        return rows.getFirst();
    }

    private static final RowMapper<ShopRegistrationStatusResponse> SHOP_STATUS_MAPPER = (rs, rowNum) ->
            new ShopRegistrationStatusResponse(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("address"),
                    rs.getString("contact_number"),
                    rs.getString("status"),
                    rs.getString("rejection_reason"),
                    rs.getObject("created_at", LocalDateTime.class));
}
