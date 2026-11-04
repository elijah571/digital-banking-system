# Ledger

Status: phase 2 in progress. Source of truth for the design: [wallet-plan.md](wallet-plan.md) sections 3.2 and 3.4.
Owner: account-service (`ledger` package). Only `LedgerPostingService` writes `ledger_account.balance` and `journal_*`. The ArchUnit test `LedgerWriteArchitectureTest` fails the build if any other class writes through `JdbcTemplate`. `accounts.balance` mirrors its wallet in the same transaction (expand, wallet-plan 3.2).

## Model

- `ledger_account`: one row per customer wallet (`owner_type = USER`, `owner_ref = accounts.id`, one per currency) and per
  system account (`owner_type = SYSTEM`, `code = NAME:CURRENCY`). Each row keeps a cached `balance`.
- `journal_entry`: one posting. Unique `idempotency_key`. `reverses_entry_id` is unique, so an entry is reversed at most once.
- `journal_line`: one debit or credit against one ledger account. Amount is always positive. `DECIMAL(19,4)`.
- Append-only: `BEFORE UPDATE` and `BEFORE DELETE` triggers on both journal tables raise SQLSTATE `45000`. A correction is a
  reversing entry, never an edit.

## Sign convention

The cached balance is kept on the account's normal side:

| Type | Normal side | Grows with |
|---|---|---|
| ASSET, EXPENSE | debit | DEBIT line |
| LIABILITY, EQUITY, REVENUE | credit | CREDIT line |

A customer wallet is a LIABILITY: a deposit (CR wallet) increases it.

## Rules

1. **Balanced.** Every entry has debits equal to credits, in each currency. Otherwise nothing is written (400).
2. **Locks.** Accounts are locked with `SELECT … FOR UPDATE` in ascending id order. Two postings that share accounts
   always lock in the same order, so they cannot deadlock.
3. **Idempotency.** The key is checked after the locks (see [ADR 0001](adr/0001-locking.md)). The same key with the same
   content returns the original entry and writes nothing. The same key with different content is a conflict (422). The
   UNIQUE index on `idempotency_key` is the backstop.
4. **Funds.** A USER account never goes below zero (422). A SYSTEM account may go negative, because clearing and suspense
   accounts are settled later.
5. **Reversal.** A reversal posts the opposite lines and sets `reverses_entry_id`. A second reversal is a conflict (409).
6. **Reconciliation identity.** Each cached balance equals the signed sum of its lines, and debits equal credits per
   currency across the journal. The test suite checks both after every scenario. The hourly job (`ReconciliationJob`) is
   in place. The admin endpoint waits for the decision in wallet-plan 8.18.

## Entries

Built now:

| Flow | Entry | Key |
|---|---|---|
| Opening-balance backfill (V4), once per existing account with a positive balance | DR OPENING_BALANCE_EQUITY:NGN, CR wallet | `backfill-{accountId}` |
| Card deposit, credit | DR PROVIDER_CLEARING:STRIPE:NGN, CR wallet. The requested amount only. | `dep-{id}-credit` |
| Card deposit, surplus or shortfall | DR PROVIDER_CLEARING:STRIPE:NGN, CR DEPOSIT_SUSPENSE:NGN. Whatever arrived and was not credited. | `dep-{id}-suspense` |
| Card deposit, Stripe fee | DR PAYMENT_FEES:NGN, CR PROVIDER_CLEARING:STRIPE:NGN. Posted when the balance transaction is known. | `dep-{id}-fee` |
| Reversal of any entry | The opposite lines, `reverses_entry_id` set | caller's key |
| Admin credit | DR ADJUSTMENT_EQUITY:NGN, CR wallet. Reason and `Idempotency-Key` required. | `adm-{Idempotency-Key}` |
| Admin deduct | DR wallet, CR ADJUSTMENT_EQUITY:NGN. Cannot take the wallet below zero. | `adm-{Idempotency-Key}` |

Planned (later slices or phases):

| Flow | Entry | Phase |
|---|---|---|
| Account opened with `initialDeposit` | DR OPENING_BALANCE_EQUITY, CR wallet | 2 |
| Transfer hold / settle / release | 3.4 | 3 |
| Outbound payout hold / settle / release / reversal | 3.4, D20, D21 | 6 |
| Inbound bank transfer | DR PROVIDER_CLEARING:PAYSTACK:NGN, CR wallet (`in-{providerReference}`) | 6c |

## Deposit amounts (D26, D27)

- The wallet is credited exactly the requested amount. Never more, never less.
- Anything received that is not credited goes to `DEPOSIT_SUSPENSE:NGN`. That covers a surplus, and the whole amount
  received on a shortfall. An admin resolves suspense later, by refund or reclassification. Each suspense entry raises an alert. The alert is not wired yet. It should be an outbox row written in the same transaction as the suspense entry. V5 has the outbox table, and `OutboxRelay` publishes outbox rows to Kafka. `CardDepositPostings` doesn't write the alert row yet; that waits for the deposit flow in phase 5.
- A shortfall is FAILED for the user, but what arrived is still booked to suspense.
- The credit never waits for Stripe's fee or FX data. The fee is its own entry, computed from the balance transaction:
  `fee_NGN = fee_settlement / exchange_rate`, rounded HALF_EVEN to 2 decimals. Nothing is converted when the fee is already in NGN.
- `CARD_DEPOSIT_MIN` is the minimum card deposit. It is enforced in the deposit flow (phase 5), not in the ledger.

## Out of scope: settlement currency and FX

Stripe settles card payments in USD. The ledger is in NGN. The following are **out of scope** for this ledger, the same as
the Paystack statement reconciliation:

- the USD settlement amounts, and the exchange gains and losses on Stripe payouts;
- reconciling `PROVIDER_CLEARING:STRIPE:NGN` against the bank statement and the Stripe payout report.

The fee entry above is the only FX conversion the ledger does. Any settlement-level difference stays in
`PROVIDER_CLEARING:STRIPE:NGN` until a provider reconciliation job handles it.

## Outbound daily usage

`outbound_daily_usage` is a wallet-level counter, not a ledger account. It counts withdrawals and external transfers
per day. When a payout is rejected, cancelled, expired, failed or reversed, the amount is subtracted in the same transaction
as the state change (fix 5 and D21). The ledger has no part in it.

## Endpoints (phase 2)

All of these are in account-service. Money is a string at the currency's scale (`"95.00"` for NGN). The database keeps four decimals.

| Route | Who | What |
|---|---|---|
| `POST /internal/v1/ledger/entries` | service token only | post an entry. Lines name a wallet by `accountNumber`, or a system account by code. 201 when new, 200 on a replay. |
| `POST /internal/v1/ledger/entries/{id}/reverse` | service token only | reverse an entry, once |
| `GET /internal/v1/ledger/entries/by-key/{key}`, `GET /internal/v1/ledger/entries/{id}` | service token only | read an entry with its lines |
| `GET /internal/v1/ledger/accounts/{accountNumber}` | service token only | currency and balance |
| `GET /api/v1/accounts/{accountNumber}/statement?afterLineId=&size=` | the owner or ADMIN | one page of lines with a running balance. Keyset on the line id; pass `nextAfterLineId` back as `afterLineId`. `size` is 1 to 200. |
| `GET /api/v1/admin/ledger/reconciliation` | ADMIN | the reconciliation checks, as `{clean, issues}` |
| `PUT /api/v1/admin/accounts/{n}/credit?amount=&reason=` and `…/deduct…` | ADMIN, with an `Idempotency-Key` header | an admin adjustment. Replays return the stored response. |

The legacy endpoints (`PUT …/deduct`, `PUT …/credit`, `GET …/balance`, `GET /{accountNumber}`) still exist for the old callers. They are dev-only and go in phase 3 (wallet-plan 8.18, 8.19).

## Who writes what

- `LedgerPostingService`: the ledger tables and the wallet mirror.
- `AuditLogWriter`: `audit_log`. `OutboxWriter`: new outbox rows. `OutboxRelay`: the sent state of outbox rows.
- `IdempotencyService`: `idempotency_record`.
- `LedgerReader`, `ReconciliationService`: read-only.

`LedgerWriteArchitectureTest` fails the build if any other class writes through `JdbcTemplate`.
