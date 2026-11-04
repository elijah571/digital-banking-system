-- V7: wallets for accounts that have none (wallet-plan 3.4, D4). Accounts opened after V4 were created without a wallet, so
-- admin adjustments and ledger transfers could not reach them. Same rules as V4, for those accounts only:
--   * one USER ledger account (LIABILITY, the account's currency), linked through accounts.ledger_account_id;
--   * if the balance is positive, one entry DR OPENING_BALANCE_EQUITY, CR wallet, key backfill-{accountId}.
-- Safe to run twice: it works on the accounts that have no wallet yet, so a second run finds none and changes nothing.
--
-- A customer wallet cannot represent a negative balance. The guard stops the migration before anything is written.

CREATE TEMPORARY TABLE orphan_accounts AS
SELECT id, currency, balance FROM accounts WHERE ledger_account_id IS NULL;

CREATE TEMPORARY TABLE repair_guard (
  no_negative_balances BOOLEAN NOT NULL,
  CONSTRAINT ck_repair_no_negative_balances CHECK (no_negative_balances = 1)
);
INSERT INTO repair_guard (no_negative_balances)
SELECT COUNT(*) = 0 FROM orphan_accounts WHERE balance < 0;
DROP TEMPORARY TABLE repair_guard;

-- 1. one wallet per orphan account
INSERT INTO ledger_account (owner_type, owner_ref, type, currency, balance, version)
SELECT 'USER', o.id, 'LIABILITY', o.currency, 0, 0
FROM orphan_accounts o;

-- 2. link each orphan to its new wallet
UPDATE accounts a
JOIN orphan_accounts o ON o.id = a.id
JOIN ledger_account la
  ON la.owner_type = 'USER' AND la.owner_ref = a.id AND la.currency = a.currency
SET a.ledger_account_id = la.id
WHERE a.ledger_account_id IS NULL;

-- 3. one opening entry per orphan with a positive balance
INSERT INTO journal_entry (type, reference, idempotency_key, created_by)
SELECT 'OPENING_BALANCE', a.account_number, CONCAT('backfill-', a.id), 'flyway:V7'
FROM accounts a
JOIN orphan_accounts o ON o.id = a.id
WHERE a.balance > 0;

INSERT INTO journal_line (entry_id, ledger_account_id, direction, amount, currency)
SELECT je.id, eq.id, 'DEBIT', a.balance, a.currency
FROM accounts a
JOIN orphan_accounts o ON o.id = a.id
JOIN journal_entry je ON je.idempotency_key = CONCAT('backfill-', a.id)
JOIN ledger_account eq ON eq.code = CONCAT('OPENING_BALANCE_EQUITY:', a.currency)
WHERE a.balance > 0;

INSERT INTO journal_line (entry_id, ledger_account_id, direction, amount, currency)
SELECT je.id, a.ledger_account_id, 'CREDIT', a.balance, a.currency
FROM accounts a
JOIN orphan_accounts o ON o.id = a.id
JOIN journal_entry je ON je.idempotency_key = CONCAT('backfill-', a.id)
WHERE a.balance > 0;

-- 4. cached balances: each new wallet mirrors its account, and the equity account is reduced by what was credited
UPDATE ledger_account la
JOIN accounts a ON a.ledger_account_id = la.id
JOIN orphan_accounts o ON o.id = a.id
SET la.balance = a.balance,
    la.version = la.version + 1;

-- MySQL cannot refer to a temporary table twice in one statement, so the sums go into their own table first.
CREATE TEMPORARY TABLE orphan_credits AS
SELECT currency, SUM(balance) AS credited FROM orphan_accounts WHERE balance > 0 GROUP BY currency;

UPDATE ledger_account eq
JOIN orphan_credits c ON eq.code = CONCAT('OPENING_BALANCE_EQUITY:', c.currency)
SET eq.balance = eq.balance - c.credited,
    eq.version = eq.version + 1;

DROP TEMPORARY TABLE orphan_credits;
DROP TEMPORARY TABLE orphan_accounts;
