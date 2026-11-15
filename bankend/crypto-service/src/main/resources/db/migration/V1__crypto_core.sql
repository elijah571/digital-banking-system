CREATE TABLE crypto_asset (
  asset_key             VARCHAR(100) NOT NULL,
  fireblocks_asset_id   VARCHAR(100) NOT NULL,
  network_code          VARCHAR(100) NOT NULL,
  display_symbol        VARCHAR(24) NOT NULL,
  display_name          VARCHAR(100) NOT NULL,
  contract_address      VARCHAR(160) NULL,
  address_mode         ENUM('ACCOUNT','DERIVED') NOT NULL DEFAULT 'ACCOUNT',
  fee_asset_key        VARCHAR(100) NULL,
  decimals              TINYINT UNSIGNED NOT NULL,
  address_pattern       VARCHAR(500) NULL,
  confirmation_threshold INT UNSIGNED NOT NULL,
  daily_withdrawal_limit_base_units DECIMAL(65,0) NOT NULL,
  manual_deposit_review BOOLEAN NOT NULL DEFAULT TRUE,
  enabled               BOOLEAN NOT NULL DEFAULT FALSE,
  created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (asset_key),
  UNIQUE KEY uk_crypto_asset_provider (fireblocks_asset_id, network_code),
  CONSTRAINT ck_crypto_asset_decimals CHECK (decimals <= 36)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customer_vault (
  user_id            VARCHAR(255) NOT NULL,
  vault_account_id   VARCHAR(100) NULL,
  provisioning_status VARCHAR(24) NOT NULL DEFAULT 'CREATING',
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_customer_vault_provider (vault_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_deposit_address (
  id                 BIGINT NOT NULL AUTO_INCREMENT,
  user_id            VARCHAR(255) NOT NULL,
  asset_key          VARCHAR(100) NOT NULL,
  vault_account_id   VARCHAR(100) NOT NULL,
  address            VARCHAR(255) NULL,
  address_tag        VARCHAR(255) NULL,
  provider_address_id VARCHAR(100) NULL,
  provisioning_status VARCHAR(24) NOT NULL DEFAULT 'CREATING',
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crypto_address_user_asset (user_id, asset_key),
  KEY ix_crypto_deposit_address_lookup (address, asset_key),
  CONSTRAINT fk_crypto_address_asset FOREIGN KEY (asset_key) REFERENCES crypto_asset (asset_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_transaction (
  id                  CHAR(36) NOT NULL,
  user_id             VARCHAR(255) NOT NULL,
  direction           ENUM('DEPOSIT','WITHDRAWAL') NOT NULL,
  asset_key           VARCHAR(100) NOT NULL,
  amount_base_units   DECIMAL(65,0) NOT NULL,
  fee_base_units      DECIMAL(65,0) NULL,
  destination_address VARCHAR(255) NULL,
  destination_tag     VARCHAR(255) NULL,
  provider_tx_id      VARCHAR(100) NULL,
  provider_status     VARCHAR(40) NULL,
  tx_hash             VARCHAR(200) NULL,
  status              VARCHAR(32) NOT NULL,
  idempotency_key     VARCHAR(255) NULL,
  request_hash        CHAR(64) NULL,
  reserved_base_units DECIMAL(65,0) NULL,
  usage_day           DATE NULL,
  requires_review     BOOLEAN NOT NULL DEFAULT FALSE,
  confirmations       INT UNSIGNED NOT NULL DEFAULT 0,
  failure_reason      VARCHAR(500) NULL,
  created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crypto_provider_tx (provider_tx_id),
  UNIQUE KEY uk_crypto_user_idempotency (user_id, idempotency_key),
  KEY ix_crypto_tx_user_time (user_id, created_at),
  KEY ix_crypto_tx_status_time (status, updated_at),
  CONSTRAINT fk_crypto_tx_asset FOREIGN KEY (asset_key) REFERENCES crypto_asset (asset_key),
  CONSTRAINT ck_crypto_tx_amount CHECK (amount_base_units > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_daily_usage (
  user_id VARCHAR(255) NOT NULL,
  asset_key VARCHAR(100) NOT NULL,
  usage_day DATE NOT NULL,
  reserved_base_units DECIMAL(65,0) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, asset_key, usage_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_provider_event (
  event_id       VARCHAR(100) NOT NULL,
  event_type     VARCHAR(100) NOT NULL,
  resource_id    VARCHAR(100) NULL,
  payload        JSON NOT NULL,
  received_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  processed_at   DATETIME(6) NULL,
  attempt_count  INT UNSIGNED NOT NULL DEFAULT 0,
  last_error     VARCHAR(500) NULL,
  PRIMARY KEY (event_id),
  KEY ix_crypto_event_pending (processed_at, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_outbox (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  event_id    CHAR(36) NOT NULL,
  topic       VARCHAR(100) NOT NULL,
  message_key VARCHAR(255) NOT NULL,
  payload     JSON NOT NULL,
  created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  sent_at     DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crypto_outbox_event (event_id),
  KEY ix_crypto_outbox_pending (sent_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_processed_event (
  consumer VARCHAR(100) NOT NULL,
  event_id VARCHAR(100) NOT NULL,
  processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (consumer, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_admin_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  admin_user_id VARCHAR(255) NOT NULL,
  action VARCHAR(40) NOT NULL,
  transaction_id CHAR(36) NOT NULL,
  reason VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id), KEY ix_crypto_audit_tx (transaction_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
