# Database Deadlock Prevention

## Implemented

The NGN ledger locks all touched account rows in ascending ID order. This deterministic order prevents opposite-direction transfers from taking the same pair of locks in reverse order. Crypto ledger account rows are also selected in ascending ID order. Durable unique keys guard journal writes.

## Operational guidance

- Keep transactions short; never call a provider while holding a ledger lock.
- Lock only rows needed for the state transition and preserve the account-ID ordering when adding posting types.
- Treat MySQL deadlock error 1213 and lock timeout 1205 as transaction rollback signals. Retry the complete idempotent request a small bounded number of times with jitter.
- Do not retry validation, insufficient-funds, authorization, or idempotency-conflict errors.
- Track deadlock rate and lock wait time. A rise can indicate a query/lock-order regression even when retries hide customer-visible errors.

## Validation

The existing transfer concurrency suite covers same-account contention and ledger balancing. Add tests for each new posting type and multi-asset fee path before changing lock behavior. Production load and deadlock testing remains outstanding.
