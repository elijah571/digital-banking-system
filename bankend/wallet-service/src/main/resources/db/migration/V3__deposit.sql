-- Card deposits (wallet-plan 3.5). A deposit is PENDING until its Stripe event is consumed (slice 5.4). The status set is closed by a CHECK.
CREATE TABLE deposit (
  id                VARCHAR(36)   NOT NULL,                  -- the deposit id; its ledger key is dep-{id}-credit
  user_id           VARCHAR(100)  NOT NULL,
  account_number    VARCHAR(20)   NOT NULL,
  amount            DECIMAL(19,2) NOT NULL,                  -- naira, two decimals; the ledger keeps four
  currency          VARCHAR(3)    NOT NULL,
  status            VARCHAR(20)   NOT NULL,
  payment_intent_id VARCHAR(100)  NULL,
  failure_reason    VARCHAR(255)  NULL,
  expires_at        DATETIME(6)   NOT NULL,
  version           BIGINT        NOT NULL DEFAULT 0,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT ck_deposit_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'EXPIRED')),
  CONSTRAINT ck_deposit_amount CHECK (amount > 0),
  KEY ix_deposit_status_expiry (status, expires_at),
  KEY ix_deposit_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Idempotency for POST /api/v1/wallet/deposits (wallet-plan 3.6). The same flow as account-service's and transaction-service's.
CREATE TABLE idempotency_record (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  scope           VARCHAR(255) NOT NULL,                    -- user id plus route
  idem_key        VARCHAR(255) NOT NULL,
  request_hash    CHAR(64)     NOT NULL,                    -- SHA-256 of the canonical request
  status          ENUM('IN_PROGRESS', 'DONE') NOT NULL,
  response_status INT          NULL,
  response_body   JSON         NULL,
  resource_id     VARCHAR(36)  NULL,                        -- the deposit this key created, so a takeover resumes it
  lease_until     DATETIME(6)  NOT NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotency_scope_key (scope, idem_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
