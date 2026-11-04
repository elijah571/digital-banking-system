-- V6: receipts the ledger refuses to credit (wallet-plan 8.19, decision 3). Expand only.
--
-- A receipt whose currency does not match the intent (a bug or a bad event) is rejected, alerted, and kept here in full,
-- so nothing is lost. It is shaped like unmatched_inbound. An admin resolves each row later, by refund or reclassification.

CREATE TABLE receipt_review (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  source             VARCHAR(50)   NOT NULL,                    -- e.g. STRIPE, PAYSTACK
  provider_reference VARCHAR(255)  NOT NULL,
  deposit_id         VARCHAR(100)  NULL,
  reason             VARCHAR(100)  NOT NULL,                    -- e.g. CURRENCY_MISMATCH
  currency           CHAR(3)       NULL,                        -- as received
  amount             DECIMAL(19,4) NULL,                        -- as received
  payload            JSON          NOT NULL,                    -- the whole receipt, as received
  received_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  status             ENUM('OPEN','RESOLVED') NOT NULL DEFAULT 'OPEN',
  resolution         VARCHAR(255)  NULL,
  resolved_by        VARCHAR(100)  NULL,
  resolved_at        DATETIME(6)   NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_receipt_review (source, provider_reference, reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
