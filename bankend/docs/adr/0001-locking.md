# ADR 0001: Locking and idempotency for ledger postings

Status: accepted (2026-11-04), implemented in account-service `LedgerPostingService`.
Context: wallet-plan 3.2 (ledger model), 3.6 (idempotency), review of 2026-11-04.

## Decision

1. **One writer.** Only `LedgerPostingService` writes `ledger_account.balance` and the `journal_*` tables. Everything
   else calls it.
2. **Lock first, then check idempotency.** A posting runs in one database transaction in this order:
   1. validate: every line amount > 0, debits = credits per currency, line currency = account currency;
   2. `SELECT … FOR UPDATE` every ledger account the entry touches, **in ascending id order**;
   3. look up the idempotency key. If it exists, return the original entry, or raise a conflict when the content
      differs. Nothing is written in either case;
   4. check the funds rule and reversal uniqueness;
   5. insert the entry and its lines, update the cached balances, commit.
3. **Funds rule.** A `USER` ledger account must never go below zero after a posting. A `SYSTEM` account may go negative
   (for example `PROVIDER_CLEARING:*` before a deposit has funded it).
4. **Reversal once.** `journal_entry.reverses_entry_id` is UNIQUE, so an entry can be reversed at most once. The check
   runs under the same locks.
5. **Append-only journal.** `BEFORE UPDATE` and `BEFORE DELETE` triggers on `journal_entry` and `journal_line` raise
   SQLSTATE `45000`.

## Why the idempotency check comes after the locks

wallet-plan 3.2 lists the steps as validate, then idempotency, then lock. That order has a race. Two requests with the
same key both read "not found" before either has inserted, then both try to insert. One of them fails on the unique
key, and the failure looks like a server error when it should be a replay. Both requests lock the same accounts, because
a key always maps to the same content, so checking under the lock serialises them. The second request then sees the
first one's entry and returns it. The concurrency test `oppositeTransfersRunningAtOnceDoNotDeadlock` and the replay
test cover this.

## Why ascending id order

Every posting locks its accounts in ascending `ledger_account.id`. Two postings that touch the same accounts therefore
always acquire the locks in the same order, which avoids the deadlock in which A→B and B→A each wait on the other.

## Consequences

- A posting holds row locks on every account it touches until commit. The system accounts (`PROVIDER_CLEARING:*`,
  `PAYOUT_PAYABLE`, `TRANSFER_IN_FLIGHT`) are hot. They are taken last and only to update the cached balance (3.2,
  hot-row note). If they become a bottleneck, the fix is to stop caching system balances and compute them from the lines.
- `innodb_lock_wait_timeout` is still the server default (50 s) in this slice. The plan's 5 s per-connection setting
  (section 7) is not applied yet.
- Tests: 50 parallel debits never overdraw; 50 opposite transfers do not deadlock; 50 mixed transfers and withdrawal
  holds never go negative and the ledger reconciles (`LedgerPostingServiceTest`).
