-- V1: wallet_db at its first version (wallet-plan 3.5; threat model T1, T11 and D-P2). The database is new, so this file
-- is the whole schema for slice 4.1. Later versions are expand-only.
--
-- One row per user. pin_hash is NULL until the user sets a PIN. The lockout columns are changed only under a row lock
-- (SELECT ... FOR UPDATE), so parallel attempts run one after another.
CREATE TABLE pin_credential (
  user_id        VARCHAR(100) NOT NULL,
  pin_hash       VARCHAR(255) NULL,
  pepper_version INT          NOT NULL DEFAULT 1,
  failed_count   INT          NOT NULL DEFAULT 0,
  lockout_level  INT          NOT NULL DEFAULT 0,
  locked_until   DATETIME(6)  NULL,
  version        BIGINT       NOT NULL DEFAULT 0,
  created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
