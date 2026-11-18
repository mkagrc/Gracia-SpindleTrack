package com.spindletrack.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {
    private static final String SELECT_ACCOUNT = """
            SELECT u.id, u.firebase_uid, u.full_name, u.email, u.role, u.is_active, u.shop_id,
                   s.status AS shop_status
            FROM users u
            LEFT JOIN shops s ON s.id = u.shop_id
            """;

    private static final RowMapper<UserAccount> ACCOUNT_MAPPER = UserAccountRepository::mapAccount;
    private final JdbcTemplate jdbcTemplate;

    public UserAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByFirebaseUid(String uid) {
        return findOne(SELECT_ACCOUNT + " WHERE u.firebase_uid = ?", uid);
    }

    public Optional<UserAccount> findByFirebaseUidForUpdate(String uid) {
        return findOne(SELECT_ACCOUNT + " WHERE u.firebase_uid = ? FOR UPDATE", uid);
    }

    public Optional<UserAccount> findByEmail(String email) {
        return findOne(SELECT_ACCOUNT + " WHERE LOWER(u.email) = LOWER(?)", email);
    }

    public Optional<String> findShopName(long shopId) {
        List<String> names = jdbcTemplate.query("SELECT name FROM shops WHERE id = ?", (rs, rowNum) -> rs.getString(1), shopId);
        return names.stream().findFirst();
    }

    public void insertFirebaseUser(String uid, String fullName, String email, String role) {
        jdbcTemplate.update("""
                INSERT INTO users (firebase_uid, full_name, email, role, is_active, shop_id)
                VALUES (?, ?, ?, ?, TRUE, NULL)
                """, uid, fullName, email, role);
    }

    public void bindAndPromotePlatformAdmin(long userId, String uid, String fullName, String email) {
        jdbcTemplate.update("""
                UPDATE users
                SET firebase_uid = ?, full_name = ?, email = ?, role = 'PLATFORM_ADMIN', shop_id = NULL
                WHERE id = ?
                """, uid, fullName, email, userId);
    }

    public void updateIdentity(long userId, String fullName, String email) {
        jdbcTemplate.update("""
                UPDATE users
                SET full_name = ?, email = ?
                WHERE id = ?
                """, fullName, email, userId);
    }

    public void promotePlatformAdmin(long userId) {
        jdbcTemplate.update("""
                UPDATE users
                SET role = 'PLATFORM_ADMIN', shop_id = NULL
                WHERE id = ?
                """, userId);
    }

    public boolean hasAttachedOrders(long userId) {
        Boolean hasOrders = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM laundry_orders WHERE account_user_id = ? LIMIT 1)",
                Boolean.class,
                userId);
        return Boolean.TRUE.equals(hasOrders);
    }

    public void convertCustomerToOwner(long userId, long shopId) {
        jdbcTemplate.update("""
                UPDATE users
                SET role = 'OWNER', shop_id = ?
                WHERE id = ? AND role = 'CUSTOMER' AND shop_id IS NULL
                """, shopId, userId);
    }

    private Optional<UserAccount> findOne(String sql, Object... args) {
        List<UserAccount> rows = jdbcTemplate.query(sql, ACCOUNT_MAPPER, args);
        return rows.stream().findFirst();
    }

    private static UserAccount mapAccount(ResultSet rs, int rowNumber) throws SQLException {
        long id = rs.getLong("id");
        long shopIdValue = rs.getLong("shop_id");
        Long shopId = rs.wasNull() ? null : shopIdValue;
        return new UserAccount(
                id,
                rs.getString("firebase_uid"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("role"),
                rs.getBoolean("is_active"),
                shopId,
                rs.getString("shop_status"));
    }
}
