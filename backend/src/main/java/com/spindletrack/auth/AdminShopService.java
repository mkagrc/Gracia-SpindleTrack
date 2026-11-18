package com.spindletrack.auth;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminShopService {
    private final JdbcTemplate jdbc;
    private final ShopDocumentRepository documents;

    public AdminShopService(JdbcTemplate jdbc, ShopDocumentRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    public record ShopListItem(long id, String name, String ownerName, String ownerEmail,
            LocalDateTime registrationDate, String status, int staffCount, int orderCount) {}
    public record ShopStaff(long id, String name, String email, String status) {}
    public record ShopDetail(long id, String name, String address, String contactNumber,
            String status, String rejectionReason, LocalDateTime registrationDate,
            Owner owner, List<ShopStaff> staff, List<ShopDocumentMetadata> documents,
            int staffCount, int orderCount) {}
    public record Owner(long id, String name, String email) {}
    public record PageResult(List<ShopListItem> content, int page, int size, long totalElements) {}

    public PageResult list(String status, String search, int page) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be zero or greater.");
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalizedStatus != null && !List.of("PENDING", "ACTIVE", "REJECTED", "DEACTIVATED").contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown shop status filter.");
        }
        String query = search == null ? "" : search.trim();
        String pattern = "%" + query + "%";
        String where = " WHERE (? IS NULL OR s.status = ?) AND (? = '' OR s.name LIKE ? OR owner.full_name LIKE ? OR owner.email LIKE ?)";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM shops s LEFT JOIN users owner ON owner.shop_id=s.id AND owner.role='OWNER'" + where,
                Long.class, normalizedStatus, normalizedStatus, query, pattern, pattern, pattern);
        int size = 20;
        List<ShopListItem> results = jdbc.query("""
                SELECT s.id, s.name, owner.full_name owner_name, owner.email owner_email,
                       s.created_at registration_date, s.status,
                       (SELECT COUNT(*) FROM users staff WHERE staff.shop_id=s.id AND staff.role='STAFF') staff_count,
                       (SELECT COUNT(*) FROM laundry_orders o WHERE o.shop_id=s.id) order_count
                FROM shops s LEFT JOIN users owner ON owner.shop_id=s.id AND owner.role='OWNER'
                """ + where + " ORDER BY CASE WHEN s.status='PENDING' THEN 0 ELSE 1 END, s.created_at DESC, s.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new ShopListItem(rs.getLong("id"), rs.getString("name"), rs.getString("owner_name"),
                        rs.getString("owner_email"), rs.getObject("registration_date", LocalDateTime.class),
                        rs.getString("status"), rs.getInt("staff_count"), rs.getInt("order_count")),
                normalizedStatus, normalizedStatus, query, pattern, pattern, pattern, size, (long) page * size);
        return new PageResult(results, page, size, total == null ? 0 : total);
    }

    public ShopDetail detail(long id) {
        List<ShopDetail> rows = jdbc.query("""
                SELECT s.id, s.name, s.address, s.contact_number, s.status, s.rejection_reason, s.created_at,
                       owner.id owner_id, owner.full_name owner_name, owner.email owner_email,
                       (SELECT COUNT(*) FROM users staff WHERE staff.shop_id=s.id AND staff.role='STAFF') staff_count,
                       (SELECT COUNT(*) FROM laundry_orders o WHERE o.shop_id=s.id) order_count
                FROM shops s LEFT JOIN users owner ON owner.shop_id=s.id AND owner.role='OWNER' WHERE s.id=?
                """, (rs, n) -> new ShopDetail(rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                rs.getString("contact_number"), rs.getString("status"), rs.getString("rejection_reason"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("owner_id") == null ? null : new Owner(rs.getLong("owner_id"), rs.getString("owner_name"), rs.getString("owner_email")),
                List.of(), List.of(), rs.getInt("staff_count"), rs.getInt("order_count")), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        ShopDetail shop = rows.getFirst();
        List<ShopStaff> staff = jdbc.query("SELECT id, full_name, email, is_active FROM users WHERE shop_id=? AND role='STAFF' ORDER BY full_name, id",
                (rs, n) -> new ShopStaff(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"), rs.getBoolean("is_active") ? "ACTIVE" : "INACTIVE"), id);
        return new ShopDetail(shop.id(), shop.name(), shop.address(), shop.contactNumber(), shop.status(),
                shop.rejectionReason(), shop.registrationDate(), shop.owner(), staff, documents.findMetadataForShop(id),
                shop.staffCount(), shop.orderCount());
    }

    @Transactional
    public void transition(long shopId, String target, String reason, long adminId) {
        List<String> statuses = jdbc.query("SELECT status FROM shops WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), shopId);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop was not found.");
        String current = statuses.getFirst();
        String expected = switch (target) {
            case "ACTIVE" -> "DEACTIVATED";
            case "DEACTIVATED" -> "ACTIVE";
            case "REJECTED", "APPROVED" -> "PENDING";
            default -> throw new IllegalArgumentException("Unsupported transition target");
        };
        if (!expected.equals(current)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot " + target.toLowerCase() + " a shop with status " + current + ".");
        String action = switch (target) {
            case "ACTIVE" -> "REACTIVATED";
            case "DEACTIVATED" -> "DEACTIVATED";
            default -> target;
        };
        String storedStatus = "APPROVED".equals(target) ? "ACTIVE" : target;
        jdbc.update("UPDATE shops SET status=?, rejection_reason=?, reviewed_by=?, reviewed_at=CURRENT_TIMESTAMP WHERE id=?",
                storedStatus, "REJECTED".equals(target) ? reason : null, adminId, shopId);
        jdbc.update("INSERT INTO shop_review_history (shop_id, action, reason, acted_by) VALUES (?, ?, ?, ?)",
                shopId, action, "REJECTED".equals(target) ? reason : null, adminId);
        // TODO: Send the owner a status-change email through Brevo when email integration is approved.
    }
}
