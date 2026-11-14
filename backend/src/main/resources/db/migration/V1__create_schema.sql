CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    firebase_uid VARCHAR(128) NULL,
    full_name VARCHAR(160) NOT NULL,
    email VARCHAR(254) NOT NULL,
    mobile_number VARCHAR(32) NULL,
    role VARCHAR(24) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_firebase_uid (firebase_uid),
    UNIQUE KEY uq_users_email (email),
    KEY idx_users_role_active (role, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE services (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_services_name (name),
    KEY idx_services_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE laundry_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_code VARCHAR(24) NOT NULL,
    customer_name VARCHAR(160) NOT NULL,
    customer_mobile VARCHAR(32) NOT NULL,
    account_user_id BIGINT NULL,
    service_id BIGINT NOT NULL,
    kilos DECIMAL(7,2) NOT NULL,
    total_price DECIMAL(10,2) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'PHP',
    stage VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    payment_timing VARCHAR(24) NOT NULL DEFAULT 'PAY_AT_PICKUP',
    notes VARCHAR(1000) NULL,
    created_by_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ready_at DATETIME NULL,
    claimed_at DATETIME NULL,
    unclaimed_flagged_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_laundry_orders_order_code (order_code),
    KEY idx_laundry_orders_stage (stage),
    KEY idx_laundry_orders_created_at (created_at),
    KEY idx_laundry_orders_account_created (account_user_id, created_at),
    KEY idx_laundry_orders_ready_unclaimed (stage, ready_at, unclaimed_flagged_at),
    CONSTRAINT fk_laundry_orders_account_user FOREIGN KEY (account_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_laundry_orders_service FOREIGN KEY (service_id) REFERENCES services (id) ON DELETE RESTRICT,
    CONSTRAINT fk_laundry_orders_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_laundry_orders_kilos_positive CHECK (kilos > 0),
    CONSTRAINT chk_laundry_orders_total_nonnegative CHECK (total_price >= 0),
    CONSTRAINT chk_laundry_orders_stage CHECK (stage IN ('RECEIVED', 'WASHING', 'DRYING', 'FOLDING', 'READY', 'CLAIMED')),
    CONSTRAINT chk_laundry_orders_payment_timing CHECK (payment_timing IN ('PAY_AT_PICKUP', 'PAY_NOW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    previous_stage VARCHAR(24) NULL,
    new_stage VARCHAR(24) NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status_history_order_changed (order_id, changed_at),
    CONSTRAINT fk_status_history_order FOREIGN KEY (order_id) REFERENCES laundry_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_status_history_user FOREIGN KEY (changed_by_user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_price_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    old_price DECIMAL(10,2) NULL,
    new_price DECIMAL(10,2) NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_price_log_order_changed (order_id, changed_at),
    CONSTRAINT fk_price_log_order FOREIGN KEY (order_id) REFERENCES laundry_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_price_log_user FOREIGN KEY (changed_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_price_log_new_nonnegative CHECK (new_price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    method VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNPAID',
    amount DECIMAL(10,2) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'PHP',
    external_reference VARCHAR(160) NULL,
    payment_url VARCHAR(1000) NULL,
    created_by_user_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_payments_order_created (order_id, created_at),
    KEY idx_payments_status (status),
    KEY idx_payments_external_reference (external_reference),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES laundry_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_payments_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_payments_method CHECK (method IN ('CASH', 'ONLINE')),
    CONSTRAINT chk_payments_status CHECK (status IN ('UNPAID', 'PENDING', 'PAID')),
    CONSTRAINT chk_payments_amount_nonnegative CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    notification_type VARCHAR(24) NOT NULL,
    channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
    recipient_email VARCHAR(254) NOT NULL,
    delivery_status VARCHAR(16) NOT NULL,
    provider_message_id VARCHAR(160) NULL,
    error_message VARCHAR(1000) NULL,
    idempotency_key VARCHAR(160) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_notification_idempotency_key (idempotency_key),
    KEY idx_notification_order_type_created (order_id, notification_type, created_at),
    CONSTRAINT fk_notification_order FOREIGN KEY (order_id) REFERENCES laundry_orders (id) ON DELETE CASCADE,
    CONSTRAINT chk_notification_type CHECK (notification_type IN ('READY', 'UNCLAIMED')),
    CONSTRAINT chk_notification_status CHECK (delivery_status IN ('SENT', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
