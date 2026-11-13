-- V6: transaction status history (wallet-plan 3.9). Append-only, in the same database transaction as each status change.
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
  KEY ix_audit_log_entity (entity_type, entity_id, id),
  KEY ix_audit_log_occurred (occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
