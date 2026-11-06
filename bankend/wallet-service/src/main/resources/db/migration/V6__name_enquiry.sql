-- A name enquiry (wallet-plan 8.4): the account holder's name, held under an opaque reference for a short time. A transfer or a withdrawal
-- must present the reference, and it is checked against the caller, the bank, the account number and the expiry. It is not used up by
-- a transfer, so a retry with the same key still works.
CREATE TABLE name_enquiry (
  ref            VARCHAR(64)   NOT NULL,              -- 128 random bits, base64url
  user_id        VARCHAR(100)  NOT NULL,
  bank_code      VARCHAR(20)   NOT NULL,
  account_number VARCHAR(20)   NOT NULL,
  account_name   VARCHAR(200)  NOT NULL,
  expires_at     DATETIME(6)   NOT NULL,
  created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (ref),
  KEY ix_name_enquiry_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
