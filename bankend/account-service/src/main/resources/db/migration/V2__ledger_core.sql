-- V2: double-entry ledger core (wallet-plan 3.2, D12, D22).
-- Expand only: nothing existing is dropped or renamed. accounts.balance stays and is still written by the old code paths.
--
-- Only LedgerPostingService writes ledger_account.balance and journal_*. The triggers below make journal rows append-only.

CREATE TABLE ledger_account (
  id          BIGINT         NOT NULL AUTO_INCREMENT,
  code        VARCHAR(100)   NULL,                                   -- SYSTEM accounts only, e.g. PROVIDER_CLEARING:STRIPE:NGN
  owner_type  ENUM('USER','SYSTEM') NOT NULL,
  owner_ref   VARCHAR(255)   NULL,                                   -- accounts.id for USER, NULL for SYSTEM
  type        ENUM('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE') NOT NULL,
  currency    CHAR(3)        NOT NULL,
  balance     DECIMAL(19,4)  NOT NULL DEFAULT 0.0000,                -- cached: the sum of signed lines (reconciled)
  version     BIGINT         NOT NULL DEFAULT 0,
  created_at  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ledger_account_code (code),
  UNIQUE KEY uk_ledger_account_owner (owner_type, owner_ref, currency),
  CONSTRAINT ck_ledger_account_owner CHECK ((owner_type = 'USER') = (owner_ref IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE journal_entry (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  type              VARCHAR(50)  NOT NULL,
  reference         VARCHAR(255) NULL,
  idempotency_key   VARCHAR(100) NOT NULL,
  correlation_id    VARCHAR(100) NULL,
  created_by        VARCHAR(100) NULL,
  reverses_entry_id BIGINT       NULL,                               -- set on a reversal; UNIQUE: one reversal per entry
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_journal_entry_idempotency (idempotency_key),
  UNIQUE KEY uk_journal_entry_reverses (reverses_entry_id),
  CONSTRAINT fk_journal_entry_reverses FOREIGN KEY (reverses_entry_id) REFERENCES journal_entry (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE journal_line (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  entry_id          BIGINT        NOT NULL,
  ledger_account_id BIGINT        NOT NULL,
  direction         ENUM('DEBIT','CREDIT') NOT NULL,
  amount            DECIMAL(19,4) NOT NULL,
  currency          CHAR(3)       NOT NULL,
  PRIMARY KEY (id),
  KEY ix_journal_line_entry (entry_id),
  KEY ix_journal_line_account (ledger_account_id, id),
  CONSTRAINT ck_journal_line_amount CHECK (amount > 0),
  CONSTRAINT fk_journal_line_entry FOREIGN KEY (entry_id) REFERENCES journal_entry (id),
  CONSTRAINT fk_journal_line_account FOREIGN KEY (ledger_account_id) REFERENCES ledger_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only: a posted journal can only be corrected by a reversing entry.
CREATE TRIGGER journal_entry_no_update BEFORE UPDATE ON journal_entry FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'journal_entry is append-only';
CREATE TRIGGER journal_entry_no_delete BEFORE DELETE ON journal_entry FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'journal_entry is append-only';
CREATE TRIGGER journal_line_no_update BEFORE UPDATE ON journal_line FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'journal_line is append-only';
CREATE TRIGGER journal_line_no_delete BEFORE DELETE ON journal_line FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'journal_line is append-only';

-- Expand the existing table. Existing rows get NGN (D12); ledger_account_id is filled by the backfill (V3).
ALTER TABLE accounts
  ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'NGN',
  ADD COLUMN ledger_account_id BIGINT NULL,
  ADD UNIQUE KEY uk_accounts_ledger_account (ledger_account_id),
  ADD CONSTRAINT fk_accounts_ledger_account FOREIGN KEY (ledger_account_id) REFERENCES ledger_account (id);

-- System accounts for NGN. SYSTEM accounts may go negative (no funds check), see 3.2.
-- The names follow NAME:CURRENCY, and provider clearing is PROVIDER_CLEARING:{provider}:{currency} (D22).
INSERT INTO ledger_account (code, owner_type, owner_ref, type, currency) VALUES
  ('OPENING_BALANCE_EQUITY:NGN', 'SYSTEM', NULL, 'EQUITY',    'NGN'),
  ('ADJUSTMENT_EQUITY:NGN',      'SYSTEM', NULL, 'EQUITY',    'NGN'),
  ('FEE_REVENUE:NGN',            'SYSTEM', NULL, 'REVENUE',   'NGN'),
  ('TRANSFER_IN_FLIGHT:NGN',     'SYSTEM', NULL, 'LIABILITY', 'NGN'),
  ('PAYOUT_PAYABLE:NGN',         'SYSTEM', NULL, 'LIABILITY', 'NGN'),
  ('PROVIDER_CLEARING:STRIPE:NGN', 'SYSTEM', NULL, 'ASSET',   'NGN');
