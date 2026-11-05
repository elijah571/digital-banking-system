-- V5: audit log, transactional outbox, consumer dedupe and idempotency records (wallet-plan 3.6, 3.8, 3.9, fix 2).
-- Expand only: new tables, nothing existing is changed.

-- Append-only: every change is written in the business transaction and is never edited or removed.
CREATE TABLE audit_log (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  occurred_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  actor          VARCHAR(100) NULL,
  action         VARCHAR(100) NOT NULL,
  entity_type    VARCHAR(50)  NOT NULL,
  entity_id      VARCHAR(100) NOT NULL,
  correlation_id VARCHAR(100) NULL,
  details        JSON         NULL,
  PRIMARY KEY (id),
  KEY ix_audit_log_entity (entity_type, entity_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';

-- Written in the same transaction as the change it describes. A relay publishes unsent rows (3.8).
CREATE TABLE outbox (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_id     CHAR(36)     NOT NULL,
  topic        VARCHAR(100) NOT NULL,
  event_type   VARCHAR(100) NOT NULL,
  aggregate_id VARCHAR(100) NOT NULL,
  payload      JSON         NOT NULL,                       -- the envelope: {eventId, type, version, occurredAt, correlationId, payload}
  created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  sent_at      DATETIME(6)  NULL,
  attempts     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event (event_id),
  KEY ix_outbox_unsent (sent_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Consumers deduplicate on the event id, per consumer (3.8).
CREATE TABLE processed_event (
  consumer     VARCHAR(100) NOT NULL,
  event_id     CHAR(36)     NOT NULL,
  processed_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (consumer, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A request claims its key with a lease (fix 2). An IN_PROGRESS row whose lease has expired may be taken over.
-- lease_until has no database default: the application always sets it from the database clock.
CREATE TABLE idempotency_record (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  scope           VARCHAR(255) NOT NULL,                    -- user id plus route
  idem_key        VARCHAR(255) NOT NULL,
  request_hash    CHAR(64)     NOT NULL,                    -- SHA-256 of the canonical request
  status          ENUM('IN_PROGRESS','DONE') NOT NULL,
  response_status INT          NULL,
  response_body   JSON         NULL,
  lease_until     DATETIME(6)  NOT NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotency_scope_key (scope, idem_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
