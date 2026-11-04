-- V3: system accounts for card deposits (wallet-plan D26, D27). Expand only.
--
-- DEPOSIT_SUSPENSE: money that arrived but was not credited to a wallet (a surplus, or the whole amount on a shortfall
--                   or mismatch). A LIABILITY until an admin refunds or reclassifies it.
-- PAYMENT_FEES:     Stripe's processing and conversion fees, booked as their own entries (dep-{id}-fee).

INSERT INTO ledger_account (code, owner_type, owner_ref, type, currency) VALUES
  ('DEPOSIT_SUSPENSE:NGN', 'SYSTEM', NULL, 'LIABILITY', 'NGN'),
  ('PAYMENT_FEES:NGN',     'SYSTEM', NULL, 'EXPENSE',   'NGN');
