-- D22 clearing account for outbound Paystack transfers (wallet-plan 8.7).
INSERT INTO ledger_account (code, owner_type, owner_ref, type, currency)
VALUES ('PROVIDER_CLEARING:PAYSTACK:NGN', 'SYSTEM', NULL, 'ASSET', 'NGN');
