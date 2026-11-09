-- Cache Paystack recipient codes by an HMAC-SHA-256 fingerprint of (bank code, account number).
-- The account number itself is not copied into the provider service's database.
CREATE TABLE paystack_recipient (
  account_fingerprint CHAR(64) NOT NULL,
  recipient_code      VARCHAR(100) NOT NULL,
  created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (account_fingerprint),
  UNIQUE KEY uq_paystack_recipient_code (recipient_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
