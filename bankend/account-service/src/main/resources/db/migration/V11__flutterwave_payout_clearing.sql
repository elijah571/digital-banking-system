-- D22/D29 clearing account for outbound Flutterwave transfers (wallet-plan 9.7). Flutterwave replaces Paystack as the external
-- payout provider; the Paystack seed (V9) is kept but unused. The name is PROVIDER_CLEARING:{provider}:{currency}, built from
-- PAYOUT_PROVIDER in wallet-service, and it is an ASSET that takes a credit on settle (3.2, 8.3).
INSERT INTO ledger_account (code, owner_type, owner_ref, type, currency)
VALUES ('PROVIDER_CLEARING:FLUTTERWAVE:NGN', 'SYSTEM', NULL, 'ASSET', 'NGN');
