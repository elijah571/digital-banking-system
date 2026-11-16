-- Durable routing/retry state for the Flutterwave-primary, Monnify-secondary payout pipeline.
ALTER TABLE outbound_payout
  MODIFY COLUMN state VARCHAR(40) NOT NULL,
  MODIFY COLUMN failure_reason VARCHAR(500) NULL,
  ADD COLUMN active_provider VARCHAR(50) NULL AFTER provider_reference,
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER active_provider,
  ADD COLUMN next_retry_at DATETIME(6) NULL AFTER retry_count,
  DROP CHECK ck_payout_state,
  ADD CONSTRAINT ck_payout_state CHECK (state IN (
    'REQUESTED', 'PENDING_APPROVAL', 'APPROVED', 'PENDING_PROVIDER', 'PROCESSING', 'AMBIGUOUS',
    'COMPLETED', 'FAILED', 'REQUIRES_MANUAL_REVIEW', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REVERSED'
  )),
  ADD CONSTRAINT ck_payout_retry_count CHECK (retry_count >= 0),
  ADD KEY ix_payout_retry_due (state, next_retry_at, updated_at);
