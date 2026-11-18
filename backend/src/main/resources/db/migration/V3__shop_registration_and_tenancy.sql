-- Additive tenancy foundation. V1 and V2 remain unchanged.
-- Legacy records from the single-shop scaffold are assigned to one ACTIVE demo shop.

CREATE TABLE shops (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(160) NOT NULL,
    address VARCHAR(500) NOT NULL,
    contact_number VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    rejection_reason VARCHAR(1000) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shops_status_created (status, created_at),
    KEY idx_shops_reviewed_by (reviewed_by),
    CONSTRAINT fk_shops_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_shops_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'DEACTIVATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO shops (name, address, contact_number, status, reviewed_at)
VALUES ('SpindleTrack Demo Laundry', 'Demo address', '09170000000', 'ACTIVE', CURRENT_TIMESTAMP);
SET @spindletrack_demo_shop_id = LAST_INSERT_ID();

-- The UID is nullable already in V1; retain that property explicitly for sample users.
ALTER TABLE users
    MODIFY COLUMN firebase_uid VARCHAR(128) NULL,
    ADD COLUMN shop_id BIGINT NULL AFTER role,
    ADD KEY idx_users_shop_role_active (shop_id, role, is_active),
    ADD CONSTRAINT fk_users_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;

-- The pre-tenancy installation represents one legacy/demo shop, so all existing users
-- are associated with it. Future platform customers may have a NULL shop_id.
UPDATE users
SET shop_id = @spindletrack_demo_shop_id
WHERE shop_id IS NULL;

CREATE TABLE shop_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(100) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_shop_documents_stored_filename (stored_filename),
    KEY idx_shop_documents_shop_uploaded (shop_id, uploaded_at),
    KEY idx_shop_documents_uploaded_by (uploaded_by),
    CONSTRAINT fk_shop_documents_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_documents_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_shop_documents_type CHECK (document_type IN ('BUSINESS_PERMIT', 'DTI_REGISTRATION', 'SHOP_PHOTO', 'OTHER')),
    CONSTRAINT chk_shop_documents_size CHECK (size_bytes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shop_review_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    reason VARCHAR(1000) NULL,
    acted_by BIGINT NULL,
    acted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shop_review_history_shop_acted (shop_id, acted_at),
    KEY idx_shop_review_history_acted_by (acted_by),
    CONSTRAINT fk_shop_review_history_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_review_history_acted_by FOREIGN KEY (acted_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_shop_review_history_action CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'DEACTIVATED', 'REACTIVATED', 'RESUBMITTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO shop_review_history (shop_id, action, reason, acted_at)
VALUES (@spindletrack_demo_shop_id, 'APPROVED', 'Demo shop provisioned as active by the tenancy migration.', CURRENT_TIMESTAMP);

-- Invitation lifecycle is separate from users.is_active.
CREATE TABLE staff_invitations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    email VARCHAR(254) NOT NULL,
    invitation_token_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    invited_by_user_id BIGINT NOT NULL,
    accepted_by_user_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    accepted_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_staff_invitations_token_hash (invitation_token_hash),
    KEY idx_staff_invitations_shop_status_created (shop_id, status, created_at),
    KEY idx_staff_invitations_shop_email (shop_id, email),
    KEY idx_staff_invitations_invited_by (invited_by_user_id),
    KEY idx_staff_invitations_accepted_by (accepted_by_user_id),
    CONSTRAINT fk_staff_invitations_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_invitations_invited_by FOREIGN KEY (invited_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_staff_invitations_accepted_by FOREIGN KEY (accepted_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_staff_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Services are shop-owned; service names need only be unique within their shop.
ALTER TABLE services
    ADD COLUMN shop_id BIGINT NULL AFTER id,
    ADD KEY idx_services_shop_active (shop_id, is_active),
    ADD CONSTRAINT fk_services_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;
UPDATE services SET shop_id = @spindletrack_demo_shop_id WHERE shop_id IS NULL;
ALTER TABLE services
    MODIFY COLUMN shop_id BIGINT NOT NULL,
    DROP INDEX uq_services_name,
    ADD UNIQUE KEY uq_services_shop_name (shop_id, name);

ALTER TABLE laundry_orders
    ADD COLUMN shop_id BIGINT NULL AFTER id,
    ADD KEY idx_laundry_orders_shop_stage_created (shop_id, stage, created_at),
    ADD CONSTRAINT fk_laundry_orders_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;
UPDATE laundry_orders SET shop_id = @spindletrack_demo_shop_id WHERE shop_id IS NULL;
ALTER TABLE laundry_orders MODIFY COLUMN shop_id BIGINT NOT NULL;

ALTER TABLE order_status_history
    ADD COLUMN shop_id BIGINT NULL AFTER id,
    ADD KEY idx_status_history_shop_order_changed (shop_id, order_id, changed_at),
    ADD CONSTRAINT fk_status_history_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;
UPDATE order_status_history h
JOIN laundry_orders o ON o.id = h.order_id
SET h.shop_id = o.shop_id
WHERE h.shop_id IS NULL;
ALTER TABLE order_status_history MODIFY COLUMN shop_id BIGINT NOT NULL;

ALTER TABLE order_price_log
    ADD COLUMN shop_id BIGINT NULL AFTER id,
    ADD KEY idx_price_log_shop_order_changed (shop_id, order_id, changed_at),
    ADD CONSTRAINT fk_price_log_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;
UPDATE order_price_log p
JOIN laundry_orders o ON o.id = p.order_id
SET p.shop_id = o.shop_id
WHERE p.shop_id IS NULL;
ALTER TABLE order_price_log MODIFY COLUMN shop_id BIGINT NOT NULL;

ALTER TABLE payments
    ADD COLUMN shop_id BIGINT NULL AFTER id,
    ADD KEY idx_payments_shop_status_created (shop_id, status, created_at),
    ADD CONSTRAINT fk_payments_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;
UPDATE payments p
JOIN laundry_orders o ON o.id = p.order_id
SET p.shop_id = o.shop_id
WHERE p.shop_id IS NULL;
ALTER TABLE payments MODIFY COLUMN shop_id BIGINT NOT NULL;

-- Preserve existing order notifications while allowing shop-level review email logs.
ALTER TABLE notification_log
    ADD COLUMN shop_id BIGINT NULL AFTER id,
    ADD KEY idx_notification_shop_type_created (shop_id, notification_type, created_at),
    ADD CONSTRAINT fk_notification_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;
UPDATE notification_log n
JOIN laundry_orders o ON o.id = n.order_id
SET n.shop_id = o.shop_id
WHERE n.shop_id IS NULL;
ALTER TABLE notification_log DROP FOREIGN KEY fk_notification_shop;
ALTER TABLE notification_log
    MODIFY COLUMN order_id BIGINT NULL,
    MODIFY COLUMN shop_id BIGINT NOT NULL,
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN ('READY', 'UNCLAIMED', 'SHOP_APPROVED', 'SHOP_REJECTED'));
ALTER TABLE notification_log
    ADD CONSTRAINT fk_notification_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;
