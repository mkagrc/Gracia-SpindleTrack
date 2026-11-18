CREATE TABLE shop_registration_rate_limits (
    ip_hash CHAR(64) NOT NULL,
    attempt_date DATE NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ip_hash, attempt_date),
    CONSTRAINT chk_shop_registration_rate_limits_count CHECK (attempt_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
