-- Separate integer-base-unit subledger. NGN tables and their currency precision remain unchanged.
CREATE TABLE crypto_ledger_account (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  asset_key  VARCHAR(100) NOT NULL,
  owner_type ENUM('USER','SYSTEM') NOT NULL,
  owner_ref  VARCHAR(255) NULL,
  account_key VARCHAR(300) NOT NULL,
  role       ENUM('AVAILABLE','RESERVED','CUSTODY','FEE_EXPENSE') NOT NULL,
  balance    DECIMAL(65,0) NOT NULL DEFAULT 0,
  version    BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crypto_ledger_user (asset_key, account_key),
  CONSTRAINT ck_crypto_ledger_owner CHECK ((owner_type='USER') = (owner_ref IS NOT NULL)),
  CONSTRAINT ck_crypto_ledger_role CHECK ((owner_type='USER' AND role IN ('AVAILABLE','RESERVED')) OR
                                          (owner_type='SYSTEM' AND role IN ('CUSTODY','FEE_EXPENSE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_journal_entry (
  id              BIGINT NOT NULL AUTO_INCREMENT,
  type            VARCHAR(40) NOT NULL,
  reference       VARCHAR(255) NULL,
  idempotency_key VARCHAR(150) NOT NULL,
  request_hash    CHAR(64) NOT NULL,
  created_by      VARCHAR(100) NOT NULL,
  created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crypto_journal_idempotency (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crypto_journal_line (
  id               BIGINT NOT NULL AUTO_INCREMENT,
  entry_id         BIGINT NOT NULL,
  ledger_account_id BIGINT NOT NULL,
  asset_key        VARCHAR(100) NOT NULL,
  direction        ENUM('DEBIT','CREDIT') NOT NULL,
  amount_base_units DECIMAL(65,0) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_crypto_journal_line_entry (entry_id),
  KEY ix_crypto_journal_line_account (ledger_account_id,id),
  CONSTRAINT ck_crypto_journal_line_positive CHECK (amount_base_units > 0),
  CONSTRAINT fk_crypto_journal_line_entry FOREIGN KEY (entry_id) REFERENCES crypto_journal_entry(id),
  CONSTRAINT fk_crypto_journal_line_account FOREIGN KEY (ledger_account_id) REFERENCES crypto_ledger_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
CREATE TRIGGER crypto_journal_entry_no_update BEFORE UPDATE ON crypto_journal_entry FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'crypto_journal_entry is append-only'$$
CREATE TRIGGER crypto_journal_entry_no_delete BEFORE DELETE ON crypto_journal_entry FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'crypto_journal_entry is append-only'$$
CREATE TRIGGER crypto_journal_line_no_update BEFORE UPDATE ON crypto_journal_line FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'crypto_journal_line is append-only'$$
CREATE TRIGGER crypto_journal_line_no_delete BEFORE DELETE ON crypto_journal_line FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'crypto_journal_line is append-only'$$
DELIMITER ;
