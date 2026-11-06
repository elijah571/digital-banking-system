-- Outbox and processed events for wallet-service (wallet-plan 3.8). Expand only: two new tables.
-- A deposit's outcome is written to the outbox in the same transaction as its processed-event row, so it is published once
-- even when the Stripe event is delivered twice.
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

CREATE TABLE processed_event (
  consumer     VARCHAR(100) NOT NULL,
  event_id     VARCHAR(100) NOT NULL,
  processed_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (consumer, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
