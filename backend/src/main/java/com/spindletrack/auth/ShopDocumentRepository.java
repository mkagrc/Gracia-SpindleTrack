package com.spindletrack.auth;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ShopDocumentRepository {
    private static final RowMapper<ShopDocumentRecord> DOCUMENT_MAPPER = (rs, rowNumber) ->
            new ShopDocumentRecord(
                    rs.getLong("id"),
                    rs.getLong("shop_id"),
                    rs.getString("document_type"),
                    rs.getString("original_filename"),
                    rs.getString("stored_filename"),
                    rs.getString("content_type"),
                    rs.getLong("size_bytes"),
                    rs.getLong("uploaded_by"),
                    rs.getObject("uploaded_at", LocalDateTime.class));

    private static final String SELECT_DOCUMENT = """
            SELECT id, shop_id, document_type, original_filename, stored_filename,
                   content_type, size_bytes, uploaded_by, uploaded_at
            FROM shop_documents
            """;

    private final JdbcTemplate jdbcTemplate;

    public ShopDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int countForShop(long shopId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_documents WHERE shop_id = ?", Integer.class, shopId);
        return count == null ? 0 : count;
    }

    public List<ShopDocumentMetadata> findMetadataForShop(long shopId) {
        return jdbcTemplate.query(SELECT_DOCUMENT + " WHERE shop_id = ? ORDER BY uploaded_at, id",
                DOCUMENT_MAPPER, shopId).stream().map(ShopDocumentRecord::metadata).toList();
    }

    public Optional<ShopDocumentRecord> findById(long documentId) {
        List<ShopDocumentRecord> rows = jdbcTemplate.query(
                SELECT_DOCUMENT + " WHERE id = ?", DOCUMENT_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    public long insert(
            long shopId,
            String documentType,
            String originalFilename,
            String storedFilename,
            String contentType,
            long sizeBytes,
            long uploadedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO shop_documents
                        (shop_id, document_type, original_filename, stored_filename, content_type, size_bytes, uploaded_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, shopId);
            statement.setString(2, documentType);
            statement.setString(3, originalFilename);
            statement.setString(4, storedFilename);
            statement.setString(5, contentType);
            statement.setLong(6, sizeBytes);
            statement.setLong(7, uploadedBy);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Document metadata insert did not return an identifier.");
        }
        return id.longValue();
    }

    public boolean delete(long shopId, long documentId, long uploadedBy) {
        return jdbcTemplate.update("""
                DELETE document_row
                FROM shop_documents document_row
                JOIN shops shop_row ON shop_row.id = document_row.shop_id
                WHERE document_row.shop_id = ? AND document_row.id = ?
                  AND document_row.uploaded_by = ? AND shop_row.status = 'REJECTED'
                """, shopId, documentId, uploadedBy) == 1;
    }
}
