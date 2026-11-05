-- 3c.4 (wallet-plan 3.8, section 4): transaction-service's outbox, and the moment a transfer's amount is held.
-- Expand-only. Transfers that exist before this migration went through the old path, which moved the money without the
-- ledger, so they are marked as held: the recovery job must never hold them again.

CREATE TABLE outbox (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  aggregate_id VARCHAR(255) NOT NULL,
  topic        VARCHAR(100) NOT NULL,
  message_key  VARCHAR(255) NOT NULL,
  payload      JSON         NOT NULL,
  created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  sent_at      DATETIME(6)  NULL,
  PRIMARY KEY (id),
  KEY ix_outbox_unsent (sent_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE transactions ADD COLUMN held_at DATETIME(6) NULL;
UPDATE transactions SET held_at = created_at WHERE held_at IS NULL;
CREATE INDEX ix_transactions_recovery ON transactions (status, held_at, created_at);
