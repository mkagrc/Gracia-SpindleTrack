package com.spindletrack.auth;

import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopRegistrationService {
    private final JdbcTemplate jdbcTemplate;
    private final UserAccountRepository users;
    private final ShopRegistrationEligibility eligibility;
    private final ShopDocumentRepository documents;
    private final ShopDocumentPolicy documentPolicy;
    private final FileStorageService fileStorage;
    private final ShopDocumentAccessPolicy documentAccess;

    public ShopRegistrationService(
            JdbcTemplate jdbcTemplate,
            UserAccountRepository users,
            ShopRegistrationEligibility eligibility,
            ShopDocumentRepository documents,
            ShopDocumentPolicy documentPolicy,
            FileStorageService fileStorage,
            ShopDocumentAccessPolicy documentAccess) {
        this.jdbcTemplate = jdbcTemplate;
        this.users = users;
        this.eligibility = eligibility;
        this.documents = documents;
        this.documentPolicy = documentPolicy;
        this.fileStorage = fileStorage;
        this.documentAccess = documentAccess;
    }

    @Transactional
    public ShopRegistrationResponse register(
            String firebaseUid, ShopRegistrationRequest request, List<MultipartFile> documentFiles) {
        List<ValidatedShopDocument> uploads = documentPolicy.validateInitial(documentFiles);
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

        List<String> storedFiles = new ArrayList<>();
        deleteStoredFilesAfterRollback(storedFiles);
        storeDocuments(shopId, account.id(), uploads, storedFiles);
        return new ShopRegistrationResponse(
                shopId, name, "PENDING", "Shop registration and verification documents submitted. Waiting for approval.");
    }

    @Transactional
    public ShopRegistrationResponse resubmit(
            String firebaseUid, ShopRegistrationRequest request, List<MultipartFile> documentFiles) {
        UserAccount account = users.findByFirebaseUidForUpdate(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Sign in before editing your shop registration."));
        requireOwnerWithShop(account);
        int existingCount = documents.countForShop(account.shopId());
        List<ValidatedShopDocument> uploads = documentPolicy.validateResubmission(
                account.shopStatus(), existingCount, documentFiles);

        String name = request.shopName().trim();
        String address = request.address().trim();
        String contactNumber = request.contactNumber().trim();
        int updated = jdbcTemplate.update("""
                UPDATE shops
                SET name = ?, address = ?, contact_number = ?, status = 'PENDING',
                    rejection_reason = NULL, reviewed_by = NULL, reviewed_at = NULL
                WHERE id = ? AND status = 'REJECTED'
                """, name, address, contactNumber, account.shopId());
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The shop status changed. Refresh the registration before resubmitting.");
        }
        jdbcTemplate.update("""
                INSERT INTO shop_review_history (shop_id, action, acted_by)
                VALUES (?, 'RESUBMITTED', ?)
                """, account.shopId(), account.id());

        List<String> storedFiles = new ArrayList<>();
        deleteStoredFilesAfterRollback(storedFiles);
        storeDocuments(account.shopId(), account.id(), uploads, storedFiles);
        return new ShopRegistrationResponse(
                account.shopId(), name, "PENDING", "Shop registration resubmitted for review.");
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
                """, (rs, rowNum) -> {
                    return new ShopRegistrationStatusResponse(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("address"),
                            rs.getString("contact_number"),
                            rs.getString("status"),
                            rs.getString("rejection_reason"),
                            rs.getObject("created_at", LocalDateTime.class),
                            List.of());
                }, firebaseUid);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No shop registration was found.");
        }
        ShopRegistrationStatusResponse row = rows.getFirst();
        return new ShopRegistrationStatusResponse(
                row.shopId(), row.shopName(), row.address(), row.contactNumber(), row.status(),
                row.rejectionReason(), row.registeredAt(), documents.findMetadataForShop(row.shopId()));
    }

    @Transactional
    public void deleteRejectedDocument(String firebaseUid, long documentId) {
        UserAccount account = users.findByFirebaseUidForUpdate(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Sign in before editing your shop documents."));
        requireOwnerWithShop(account);
        documentPolicy.requireRejectedForDocumentEdit(account.shopStatus());
        ShopDocumentRecord document = documents.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document was not found."));
        if (document.shopId() != account.shopId() || document.uploadedBy() != account.id()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only edit documents you uploaded for your rejected shop.");
        }
        if (!documents.delete(account.shopId(), documentId, account.id())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document was not found.");
        }
        deleteFileAfterCommit(document.storedFilename());
    }

    @Transactional(readOnly = true)
    public ShopDocumentDownload openDocument(long documentId, Long requesterId, String role) {
        ShopDocumentRecord document = documents.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document was not found."));
        documentAccess.requireCanOpen(role, requesterId, document.uploadedBy());
        try {
            InputStream content = fileStorage.open(document.storedFilename());
            return new ShopDocumentDownload(
                    content, document.originalFilename(), document.contentType(), document.sizeBytes());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The private verification document is unavailable.");
        }
    }

    private void storeDocuments(
            long shopId, long userId, List<ValidatedShopDocument> uploads, List<String> storedFiles) {
        for (ValidatedShopDocument upload : uploads) {
            String storedFilename;
            try (InputStream input = upload.file().getInputStream()) {
                storedFilename = fileStorage.store(input, upload.type().extension());
            } catch (FileStorageConfigurationException exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Private document storage is not configured. Set FILE_STORAGE_PATH to an absolute folder outside the repository.");
            } catch (IOException exception) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "A verification document could not be stored.");
            }
            storedFiles.add(storedFilename);
            documents.insert(
                    shopId,
                    "OTHER",
                    upload.originalFilename(),
                    storedFilename,
                    upload.type().contentType(),
                    upload.sizeBytes(),
                    userId);
        }
    }

    private static void requireOwnerWithShop(UserAccount account) {
        if (!account.active()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is inactive.");
        }
        if (!"OWNER".equals(account.role()) || account.shopId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a shop owner can change this registration.");
        }
    }

    private void deleteStoredFilesAfterRollback(List<String> storedFiles) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        storedFiles.forEach(ShopRegistrationService.this::deleteQuietly);
                    }
                }
            });
        }
    }

    private void deleteFileAfterCommit(String storedFilename) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(storedFilename);
                }
            });
        } else {
            deleteQuietly(storedFilename);
        }
    }

    private void deleteQuietly(String storedFilename) {
        try {
            fileStorage.delete(storedFilename);
        } catch (IOException ignored) {
            // Database metadata stays authoritative; no private storage path is exposed to callers.
        }
    }
}
