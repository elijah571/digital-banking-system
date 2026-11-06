-- The clearing account for the simulated payout provider (wallet-plan 8.7, D22). The name is PROVIDER_CLEARING:{provider}:{currency}, and
-- it is an ASSET, as Stripe's clearing account is (V2). A settled payout takes a credit here (8.3).
INSERT INTO ledger_account (code, owner_type, owner_ref, type, currency) VALUES
  ('PROVIDER_CLEARING:SIMULATOR:NGN', 'SYSTEM', NULL, 'ASSET', 'NGN');
