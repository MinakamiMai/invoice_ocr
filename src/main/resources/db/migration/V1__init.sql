-- Invoice OCR initial schema (MySQL 8.0+)

CREATE TABLE app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE invoice (
    id                    CHAR(36)      NOT NULL,
    owner_id              BIGINT        NOT NULL,
    invoice_type          VARCHAR(32)   NULL,
    invoice_code          VARCHAR(20)   NULL,
    invoice_number        VARCHAR(20)   NULL,
    invoice_date          DATE          NULL,
    machine_number        VARCHAR(32)   NULL,
    buyer_name            VARCHAR(300)  NULL,
    buyer_tax_id          VARCHAR(20)   NULL,
    buyer_address_phone   VARCHAR(500)  NULL,
    buyer_bank_account    VARCHAR(500)  NULL,
    seller_name           VARCHAR(300)  NULL,
    seller_tax_id         VARCHAR(20)   NULL,
    seller_address_phone  VARCHAR(500)  NULL,
    seller_bank_account   VARCHAR(500)  NULL,
    total_amount          DECIMAL(18,2) NULL,
    total_tax             DECIMAL(18,2) NULL,
    grand_total           DECIMAL(18,2) NULL,
    grand_total_cn        VARCHAR(100)  NULL,
    tax_rate              VARCHAR(32)   NULL,
    check_code            VARCHAR(20)   NULL,
    payee                 VARCHAR(100)  NULL,
    reviewer_name         VARCHAR(100)  NULL,
    drawer                VARCHAR(100)  NULL,
    status                VARCHAR(32)   NOT NULL,
    duplicate_flag        TINYINT(1)    NOT NULL DEFAULT 0,
    duplicate_type        VARCHAR(32)   NULL,
    duplicate_of_id       CHAR(36)      NULL,
    verification_status   VARCHAR(32)   NULL,
    verified_at           DATETIME      NULL,
    needs_review_reason   VARCHAR(1000) NULL,
    fingerprint_exact     CHAR(64)      NULL,
    fingerprint_fallback  CHAR(64)      NULL,
    fingerprint_fields    CHAR(64)      NULL,
    field_confidence      JSON          NULL,
    raw_file_ref          VARCHAR(512)  NOT NULL,
    original_filename     VARCHAR(255)  NOT NULL,
    file_hash             CHAR(64)      NOT NULL,
    file_type             VARCHAR(16)   NOT NULL,
    file_size             BIGINT        NOT NULL,
    created_at            DATETIME      NOT NULL,
    updated_at            DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_code_number (invoice_code, invoice_number),
    KEY idx_invoice_number (invoice_number),
    KEY idx_invoice_date (invoice_date),
    KEY idx_seller_name (seller_name),
    KEY idx_seller_tax_id (seller_tax_id),
    KEY idx_buyer_name (buyer_name),
    KEY idx_buyer_tax_id (buyer_tax_id),
    KEY idx_status (status),
    KEY idx_duplicate (duplicate_flag),
    KEY idx_verification (verification_status),
    KEY idx_fingerprint_exact (fingerprint_exact),
    KEY idx_fingerprint_fallback (fingerprint_fallback),
    KEY idx_fingerprint_fields (fingerprint_fields),
    KEY idx_file_hash (file_hash),
    KEY idx_owner (owner_id),
    KEY idx_created (created_at),
    CONSTRAINT fk_invoice_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE invoice_item (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id  CHAR(36)      NOT NULL,
    line_no     INT           NOT NULL,
    item_name   VARCHAR(500)  NULL,
    specification VARCHAR(200) NULL,
    unit        VARCHAR(50)   NULL,
    quantity    DECIMAL(18,4) NULL,
    unit_price  DECIMAL(18,4) NULL,
    amount      DECIMAL(18,2) NULL,
    tax_rate    VARCHAR(32)   NULL,
    tax_amount  DECIMAL(18,2) NULL,
    PRIMARY KEY (id),
    KEY idx_item_invoice (invoice_id),
    CONSTRAINT fk_item_invoice FOREIGN KEY (invoice_id) REFERENCES invoice (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ocr_task (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    invoice_id        CHAR(36)     NOT NULL,
    owner_id          BIGINT       NOT NULL,
    owner_username    VARCHAR(64)  NULL,
    file_hash         CHAR(64)     NULL,
    file_ref          VARCHAR(512) NULL,
    file_type         VARCHAR(16)  NULL,
    file_size         BIGINT       NULL,
    original_filename VARCHAR(255) NULL,
    invoice_type_hint VARCHAR(32)  NULL,
    engine_used       VARCHAR(64)  NULL,
    status            VARCHAR(32)  NOT NULL,
    started_at        DATETIME     NULL,
    finished_at       DATETIME     NULL,
    error_message     VARCHAR(2000) NULL,
    raw_result_ref    VARCHAR(512) NULL,
    duplicate_of_id   CHAR(36)     NULL,
    duplicate_type    VARCHAR(32)  NULL,
    message           VARCHAR(500) NULL,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_task_invoice (invoice_id),
    KEY idx_task_owner (owner_id),
    KEY idx_task_file_hash (file_hash),
    KEY idx_task_status (status),
    KEY idx_task_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE review_record (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id   CHAR(36)      NOT NULL,
    field_name   VARCHAR(64)   NOT NULL,
    old_value    TEXT          NULL,
    new_value    TEXT          NULL,
    reviewer     VARCHAR(64)   NOT NULL,
    reviewed_at  DATETIME      NOT NULL,
    conclusion   VARCHAR(32)   NOT NULL,
    comment      VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    KEY idx_review_invoice (invoice_id),
    KEY idx_review_time (reviewed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE verification_record (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    invoice_id           CHAR(36)    NOT NULL,
    channel              VARCHAR(64) NOT NULL,
    request_payload_hash CHAR(64)    NOT NULL,
    result_status        VARCHAR(32) NOT NULL,
    result_summary       TEXT        NULL,
    verified_at          DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_verification_invoice (invoice_id),
    KEY idx_verification_time (verified_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL,
    username    VARCHAR(64)  NULL,
    role        VARCHAR(32)  NULL,
    action      VARCHAR(64)  NOT NULL,
    object_type VARCHAR(64)  NULL,
    object_id   VARCHAR(64)  NULL,
    ip          VARCHAR(64)  NULL,
    detail      TEXT         NULL,
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_time (created_at),
    KEY idx_audit_user (username),
    KEY idx_audit_action (action),
    KEY idx_audit_object (object_type, object_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE system_config (
    config_key   VARCHAR(100) NOT NULL,
    config_value TEXT         NULL,
    encrypted    TINYINT(1)   NOT NULL DEFAULT 0,
    description  VARCHAR(500) NULL,
    updated_at   DATETIME     NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE dedup_registry (
    dedup_key  VARCHAR(100) NOT NULL,
    invoice_id CHAR(36)     NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (dedup_key),
    KEY idx_dedup_invoice (invoice_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
