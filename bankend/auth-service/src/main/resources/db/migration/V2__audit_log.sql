-- V2: audit_log (wallet-plan 3.9). Expand only: a new table. The password checks for the wallet write one row each, in
-- the same database. The table is append-only: UPDATE and DELETE are refused by the triggers below.
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
