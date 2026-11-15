# MySQL Transaction Integrity

## Existing controls

- MySQL uses InnoDB and separate service databases.
- Flyway owns schema evolution; Hibernate validates instead of mutating production schemas.
- NGN ledger postings validate positive lines and debit/credit balance per currency, acquire `SELECT ... FOR UPDATE` locks ordered by ledger-account ID, enforce non-negative USER balances, store an idempotency key, append journal lines, and update cached balances in one transaction.
- Crypto postings use a separate integer-base-unit journal and idempotency table; they do not convert asset identifiers into Java `Currency` values.
- Existing account and transfer integration tests assert ledger invariants. See account-service `LedgerPostingService` and `TransferLedgerTest`.

## Operational requirements

- Keep every financial table on InnoDB. Do not use floating point for monetary values.
- Keep idempotency uniqueness and journal constraints in MySQL. Redis must not be the only duplicate barrier.
- Retry deadlock victims only at the whole idempotent operation boundary, with bounded backoff. Do not retry a partially executed provider side effect.
- Monitor pool saturation, lock wait time, deadlocks, replication lag, and failed migrations.
- For restore verification, reconcile per-entry debits/credits and compare every cached balance to its journal-derived balance before reopening writes.

## Production risk

The repository configures one MySQL container for local Compose. Production replication, point-in-time recovery, encryption keys, and immutable backup retention must be supplied by the deployment platform and tested in an isolated restore exercise.
