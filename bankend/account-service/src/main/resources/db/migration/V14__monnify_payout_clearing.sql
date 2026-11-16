-- Monnify is the secondary NGN payout rail. The active provider owns the clearing credit on settle.
INSERT INTO ledger_account (code, owner_type, owner_ref, type, currency)
VALUES ('PROVIDER_CLEARING:MONNIFY:NGN', 'SYSTEM', NULL, 'ASSET', 'NGN');
