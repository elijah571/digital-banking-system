ALTER TABLE outbound_payout
  ADD COLUMN idempotency_key VARCHAR(255) NULL;

UPDATE outbound_payout
SET idempotency_key = CONCAT('legacy-outbound-', id)
WHERE idempotency_key IS NULL;

ALTER TABLE outbound_payout
  MODIFY idempotency_key VARCHAR(255) NOT NULL,
  ADD CONSTRAINT uq_outbound_payout_user_idempotency UNIQUE (user_id, idempotency_key);
