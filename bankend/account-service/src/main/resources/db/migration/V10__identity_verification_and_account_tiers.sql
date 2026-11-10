ALTER TABLE accounts
  ADD COLUMN account_tier VARCHAR(24) NOT NULL DEFAULT 'STANDARD';

CREATE TABLE account_verification (
  owner_id                 VARCHAR(255) NOT NULL,
  provider                 VARCHAR(32)  NOT NULL,
  identity_status          VARCHAR(24)  NOT NULL DEFAULT 'NOT_STARTED',
  upgrade_status           VARCHAR(24)  NOT NULL DEFAULT 'NOT_STARTED',
  bvn_last_four            CHAR(4)      NULL,
  document_type            VARCHAR(32)  NULL,
  address_document_type    VARCHAR(40)  NULL,
  address_status           VARCHAR(24)  NOT NULL DEFAULT 'NOT_STARTED',
  identity_face_match_score DECIMAL(5,2) NULL,
  identity_liveness_score  DECIMAL(5,2) NULL,
  upgrade_face_match_score DECIMAL(5,2) NULL,
  upgrade_liveness_score   DECIMAL(5,2) NULL,
  identity_consent_at      DATETIME(6)  NULL,
  identity_verified_at     DATETIME(6)  NULL,
  upgrade_consent_at       DATETIME(6)  NULL,
  address_verified_at      DATETIME(6)  NULL,
  account_upgraded_at      DATETIME(6)  NULL,
  created_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (owner_id),
  KEY ix_account_verification_status (upgrade_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE verification_attempt (
  owner_id         VARCHAR(255) NOT NULL,
  purpose          VARCHAR(24)  NOT NULL,
  last_attempt_at  DATETIME(6)  NULL,
  PRIMARY KEY (owner_id, purpose)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
