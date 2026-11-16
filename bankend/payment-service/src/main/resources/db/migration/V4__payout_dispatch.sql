-- Payment-service owns external provider dispatch/retry coordination; wallet-service remains owner of payout money and ledger state.
CREATE TABLE payout_dispatch (
  payout_key          VARCHAR(100) NOT NULL,
  event_id            VARCHAR(100) NOT NULL,
  request_payload     JSON         NOT NULL,
  active_provider     VARCHAR(50)  NOT NULL,
  state               VARCHAR(40)  NOT NULL,
  retry_count         INT          NOT NULL DEFAULT 0,
  next_retry_at       DATETIME(6)  NULL,
  provider_reference  VARCHAR(100) NULL,
  failure_reason      VARCHAR(500) NULL,
  created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (payout_key),
  UNIQUE KEY uq_payout_dispatch_event (event_id),
  KEY ix_payout_dispatch_due (state, next_retry_at, updated_at),
  CONSTRAINT ck_payout_dispatch_retry_count CHECK (retry_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
