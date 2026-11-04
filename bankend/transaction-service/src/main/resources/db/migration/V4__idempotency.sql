-- V4: idempotency records for POST /api/v1/transactions (wallet-plan 3.6). Expand only: a new table, nothing existing changes.
-- transaction_id is written in the same local transaction that saves the transfer, so a retry after a crash finds the transfer
-- its first attempt made, and never makes a second one.
CREATE TABLE idempotency_record (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  scope           VARCHAR(255) NOT NULL,                    -- user id (or "internal") plus the route
  idem_key        VARCHAR(255) NOT NULL,
  request_hash    CHAR(64)     NOT NULL,                    -- SHA-256 of the canonical request
  status          ENUM('IN_PROGRESS','DONE') NOT NULL,
  response_status INT          NULL,
  response_body   JSON         NULL,
  transaction_id  VARCHAR(255) NULL,
  lease_until     DATETIME(6)  NOT NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotency_scope_key (scope, idem_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
