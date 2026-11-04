-- V4: opening-balance backfill (wallet-plan 3.4, D4, D5). Runs once, on an existing database or a fresh one.
--
-- For every existing account:
--   * one USER ledger account (LIABILITY, the account's currency), linked through accounts.ledger_account_id;
--   * if the balance is positive, one entry DR OPENING_BALANCE_EQUITY, CR wallet, key backfill-{accountId}.
-- Afterwards every wallet's cached balance equals accounts.balance, and OPENING_BALANCE_EQUITY holds the negative of the
-- total credited, so the journal balances.
--
-- A customer wallet cannot represent a negative balance. The guard below stops the migration before anything is written
-- if any account is negative.

CREATE TEMPORARY TABLE backfill_guard (
  no_negative_balances BOOLEAN NOT NULL,
  CONSTRAINT ck_backfill_no_negative_balances CHECK (no_negative_balances = 1)
);
INSERT INTO backfill_guard (no_negative_balances)
SELECT COUNT(*) = 0 FROM accounts WHERE balance < 0;
DROP TEMPORARY TABLE backfill_guard;

-- 1. one wallet per account
INSERT INTO ledger_account (owner_type, owner_ref, type, currency, balance, version)
SELECT 'USER', a.id, 'LIABILITY', a.currency, 0, 0
FROM accounts a
WHERE a.ledger_account_id IS NULL;

-- 2. link each account to its wallet
UPDATE accounts a
JOIN ledger_account la
  ON la.owner_type = 'USER' AND la.owner_ref = a.id AND la.currency = a.currency
SET a.ledger_account_id = la.id
WHERE a.ledger_account_id IS NULL;

-- 3. one opening entry per account with a positive balance
INSERT INTO journal_entry (type, reference, idempotency_key, created_by)
SELECT 'OPENING_BALANCE', a.account_number, CONCAT('backfill-', a.id), 'flyway:V4'
FROM accounts a
WHERE a.balance > 0;

INSERT INTO journal_line (entry_id, ledger_account_id, direction, amount, currency)
SELECT je.id, eq.id, 'DEBIT', a.balance, a.currency
FROM accounts a
JOIN journal_entry je ON je.idempotency_key = CONCAT('backfill-', a.id)
JOIN ledger_account eq ON eq.code = CONCAT('OPENING_BALANCE_EQUITY:', a.currency);

INSERT INTO journal_line (entry_id, ledger_account_id, direction, amount, currency)
SELECT je.id, a.ledger_account_id, 'CREDIT', a.balance, a.currency
FROM accounts a
JOIN journal_entry je ON je.idempotency_key = CONCAT('backfill-', a.id);

-- 4. cached balances: wallets mirror accounts.balance, and the equity account holds the negative of what was credited
UPDATE ledger_account la
JOIN accounts a ON a.ledger_account_id = la.id
SET la.balance = a.balance,
    la.version = la.version + 1;

UPDATE ledger_account eq
SET eq.balance = -(SELECT COALESCE(SUM(a.balance), 0) FROM accounts a WHERE a.balance > 0 AND a.currency = eq.currency),
    eq.version = eq.version + 1
WHERE eq.code LIKE 'OPENING_BALANCE_EQUITY:%';
