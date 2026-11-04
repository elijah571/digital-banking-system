# Wallet plan: deposits, withdrawals, double-entry ledger

Status: **approved 2026-11-03** with the changes in section 0. All other decisions use the recommended option.
Scope change 2026-11-04: **section 8 approved 2026-11-04 with the review changes in 8.16.** Phases 1–4 are being built. Phase 5 waits for D26 and D27 (8.8, 8.16).
Date: 2026-11-03 (scope change 2026-11-04)

Contents: 0. Approved changes · 1. What exists today · 2. Bugs found while reading · 3. Design · 4. Changes per service ·
5. Phases · 6. Conflicts between the spec and the code (decisions needed) · 7. Risks · 8. Scope change: external transfers and bank transfers

---

## 0. Approved changes (these override anything below)

**Decisions**
- **D5:** one `ADJUSTMENT_EQUITY` system account (a 7th seed) is the counter-account for **both** admin credit and admin deduct. An admin deduct isn't revenue, so `FEE_REVENUE` is not used for it.
- **D6:** as recommended, plus OTP events go on their own topic, `notification.otp` (retention 1 hour, `retention.ms=3600000`), **never** on the transfer topics. `transaction.otp.generated` is retired: it gets no new events, and its consumer is kept for one release (expand/contract).
- **D12: resolved, NGN.** All accounts are NGN. The opening-balance backfill, the system accounts and `LEDGER_CURRENCIES` use NGN (see section 6).

**Fixes added to the design**
1. **PIN brute force through parallel requests.** Each failed attempt is counted with an atomic
   `UPDATE pin_credential SET failed_count = failed_count + 1, locked_until = … WHERE user_id = ? AND (locked_until IS NULL OR locked_until < now())`
   inside a transaction that first takes `SELECT … FOR UPDATE` on the row. The check, the verify and the count happen under that lock, so attempts for one user run one after another.
   Test: 50 parallel wrong PINs → `failed_count` = 5 → locked, and the correct PIN is still rejected while locked.
2. **Stuck idempotency records.**
   - `idempotency_record` gets `lease_until` (default now + 30 s). A request that finds IN_PROGRESS with an expired lease may take the row over with a conditional `UPDATE … WHERE lease_until < now()`.
   - `deposit.idempotency_key` and `outbound_payout.idempotency_key` are UNIQUE per user. A retry after the lease finds the existing resource and returns it instead of creating a new one.
   - Test: kill the handler after its commit but before DONE, wait for the lease to expire, retry → the same resource comes back, and there's exactly one ledger entry.
3. **Payout recovery never pays twice.** `PayoutProvider` gains `getStatus(withdrawalId)`. Recovery for PROCESSING asks first and only re-sends if the provider answers `NOT_FOUND`. The simulator keeps sends keyed by withdrawalId (idempotent).
   Test: crash right after `send()`, recovery runs → exactly one payout.
4. **Least privilege for service tokens.**
   - `sub` = the calling service and `aud` = the target service.
   - Each internal endpoint has an allow-list:
     - ledger posting: `wallet-service`, `transaction-service`;
     - ledger and account reads: `wallet-service`, `transaction-service`, `fraud-detection-service`, `payment-service`;
     - payment intents: `wallet-service`;
     - fraud check: `wallet-service`;
     - verify-password: `wallet-service`;
     - PIN verify: `transaction-service`.
   - notification-service does **not** get `INTERNAL_JWT_SECRET`.
   - Test: a valid token from the wrong caller or for the wrong audience → 403.
5. **Daily outbound usage is given back.** When an outbound payout (withdrawal or external transfer) is rejected, cancelled, expired, failed or reversed (REVERSED per D21), the amount is subtracted from `outbound_daily_usage` (on the payout's own day) **in the same transaction** as the state change. Documented in `ledger.md`, tested for each of those transitions.

**Changes found during implementation**
- **MySQL is pinned to `mysql:9.7.4` (current LTS), not `26.10`.** Updated by the 2026-11-04 review. 26.10 is an innovation release, and Flyway's docs don't list it. 9.7 is the current LTS line (premier support to 2031). The 26.10 server can't be read by 9.7, so the application databases were dumped first (outside the repo) and restored into a new 9.7.4 volume, `digital-banking-system_mysql_data_lts97`. The row counts match the 26.10 server. The old volume, `digital-banking-system_mysql_data`, is kept, not reused. Flyway's docs list 9.4 as the newest verified version. On 9.7.4 Flyway logs a "newer than verified" warning and applies V1 and V2 without errors (test run 2026-11-04). Fallback if that ever fails: 8.4 LTS. Compose and Testcontainers use the same tag.

## 1. What exists today

| Service | DB | Money-relevant facts |
|---|---|---|
| account-service 8081 | `account_db.accounts` (17 rows) | `balance DECIMAL(15,2)` changed in place by `PUT /{n}/deduct?amount=` and `/credit?amount=`. There's no ledger, no currency, no `@Version` and no row lock: it does a read-modify-write under REPEATABLE READ, so two parallel deducts can both pass the funds check. Kafka consumers: `transaction.completed` credits the receiver, `fraud.detected` blocks the account. Accounts are addressed by a 10-digit `accountNumber`; `id` is a UUID. `dailyTransactionLimit` is stored but never enforced. |
| transaction-service 8082 | `transaction_db.transactions` | The saga works like this: a Feign `deduct` runs, then a `transactions` row is saved, then `transaction.initiated` is published. Fraud sends back `fraud.check.clean` or `transaction.verification.required`. The OTP goes into Redis **in plaintext**, is generated with `java.util.Random`, and is put **in the Kafka payload**. If the OTP is right, `transaction.completed` is published and account-service credits the receiver. A wrong OTP (on the first try) publishes `fraud.detected`, Feign `credit` refunds the sender, and the status becomes FLAGGED. An expired OTP refunds the sender. |
| fraud-detection-service 8084 | `fraud_detection_db` (empty) | Stateless rules (velocity, 5× running average, > 90 % of balance). Its state lives **only in Redis**. It reads the balance through an unauthenticated `GET /accounts/{n}/balance`. Kafka only; it has no HTTP API. |
| payment-service 8085 | `payment_db.payments` | Creates a Stripe PaymentIntent (hard-coded USD). The webhook verifies the signature and publishes `payment.completed`/`payment.failed`. **Nothing consumes `payment.completed`**, so a Stripe payment never reaches a balance. |
| auth-service 8083 | `auth_db.users`, `auth_tokens` | HS256 JWT with claims `iss=elibanking-auth`, `sub`, `email`, `role`, 15 min TTL. Refresh tokens are opaque and single-use. Passwords use BCrypt(12). Role changes go through `PATCH /api/v1/users/{id}`. |
| notification-service 8086 | none | Kafka → Gmail SMTP or the log. In dev (`MAIL_LOG_CONTENT=true`) it logs OTPs, verification links and reset tokens. |
| API-GATEWAY 8080 | — | Validates the JWT and enforces roles **in one place** (`SecurityConfig`). It strips client-sent `X-User-*` headers and sets them again from the token. Redis rate limiter per user or IP. Combined Swagger UI. |

How the services are put together:
- There is **no Flyway anywhere**. Every service uses `ddl-auto: update`, and only auth sets `open-in-view: false`.
- Every service connects to MySQL as **root**.
- The services trust `X-User-*` headers. A request with **no** headers counts as an "internal" call and skips every ownership check. Ports 8081-8086 are published in dev.
- The Dockerfiles are single-stage and copy `target/*.jar`, so you need a local JDK and `scripts/build.sh` first. The app containers have no healthchecks, and the JRE image has no curl or wget.
- The images are `mysql:latest` (currently **26.10**, an innovation release), `redis:latest` and `apache/kafka:latest`.
- There are no shared libraries. Each service is its own Maven project with its own `mvnw`.
- Tests: each service has a single `contextLoads()` test, which needs a running MySQL, Redis and Kafka. The 107 checks in `scripts/smoke-test.sh` all pass today. There is no CI. The remote is `github.com/elijah571/digital-banking-system`, and the git root is the parent folder (`bankend/` and `frontend/`).
- The README documents that `GET /accounts/{n}/balance` returns a JSON number, and the smoke test asserts `.==500`.

## 2. Bugs found while reading (all fixed by the new design)

1. **Lost money on transfer.** The sender is debited in account-service, which commits, *before* the transaction row exists. If saving the row or the Kafka send fails, the money is gone and nothing records it.
2. **Double-spend race.** `deductBalance` does read → check → write with no lock and no version. Parallel transfers can overdraw an account.
3. **Lost or duplicated receiver credit.** The `transaction.completed` consumer catches every exception and only logs it, so a failed credit is lost for good. A Kafka redelivery credits the receiver twice.
4. **Stuck transfers.** If the fraud consumer throws, it logs the error and the transfer stays PROCESSING forever with the sender already debited. Nothing recovers it.
5. **OTP verify race.** Two concurrent `/verify` calls can both see PENDING_VERIFICATION, so the receiver is credited twice or the transfer is completed and refunded at once.
6. **Stripe webhook.** Processing errors are swallowed and the endpoint returns 200, so Stripe never retries. A replayed event publishes `payment.completed` twice. And nothing ever credits a payment.
7. **Weak OTP handling.** The OTP comes from `Random`, is stored in plaintext, travels in Kafka and in logs, and blocks the account on the first wrong attempt.
8. **Admin credit and deduct need no reason and leave no audit trail.** Opening an account creates money out of nothing (`initialDeposit`).
9. **Anyone who can reach port 8081 can credit any account.** These calls count as internal, so ownership checks are skipped.
10. payment-service's CORS setup is `allowedOriginPatterns("*")` with credentials. It's harmless behind the gateway, but wrong.

---

## 3. Design

### 3.1 Who owns money: account-service is the ledger owner (the spec's default, which I agree with)

Why not move the ledger into wallet-service:
- account-service already owns account status (ACTIVE/BLOCKED), and the ledger has to check status and balance **under the same row lock**. Splitting status and balance across two databases would bring back exactly the race we're removing.
- Transfers, admin credit/deduct, deposits and withdrawals all need the ledger. Leaving it in the existing service means one fewer cross-service dependency for transfers.

The rule: **only account-service's `LedgerPostingService` writes `ledger_account.balance` or `journal_*`.** Everyone else goes through
`POST /internal/v1/ledger/entries`, which requires a SERVICE token and isn't routed by the gateway. An ArchUnit test makes sure no other class writes those
tables. The DB grants enforce it too (D10).

### 3.2 Ledger model (account_db)

```
ledger_account(id BIGINT PK, code VARCHAR UNIQUE NULL,         -- code for SYSTEM accounts, e.g. PROVIDER_CLEARING:STRIPE:NGN
               owner_type USER|SYSTEM, owner_ref VARCHAR NULL, -- accounts.id for USER
               type ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE,
               currency CHAR(3), balance DECIMAL(19,4), version BIGINT, created_at)
journal_entry (id BIGINT PK, type VARCHAR, reference VARCHAR, idempotency_key VARCHAR(100) UNIQUE,
               correlation_id VARCHAR, created_by VARCHAR, reverses_entry_id BIGINT NULL UNIQUE, created_at)
journal_line  (id BIGINT PK, entry_id FK, ledger_account_id FK, direction DEBIT|CREDIT,
               amount DECIMAL(19,4) CHECK (amount > 0), currency CHAR(3))
accounts      + ledger_account_id BIGINT NULL UNIQUE, + currency CHAR(3) NOT NULL DEFAULT 'NGN'   (expand; D12)
```
- Sign convention: the balance is kept on the account's normal side. ASSET/EXPENSE accounts increase with a debit, LIABILITY/EQUITY/REVENUE accounts with a credit.
  A customer wallet is a LIABILITY, so a deposit (CR wallet) increases it.
- `BEFORE UPDATE`/`BEFORE DELETE` triggers on `journal_entry` and `journal_line` run `SIGNAL SQLSTATE '45000'`.
- One posting runs as one DB transaction:
  1. validate that debits = credits for every currency;
  2. check the idempotency key (if it already exists, return the original entry and post nothing);
  3. `SELECT … FOR UPDATE` the ledger accounts **in ascending id order**;
  4. check the status and funds rules;
  5. insert the lines;
  6. update the cached balances;
  7. write the audit row;
  8. write the outbox row `ledger.entry.posted`.
- System accounts are created per currency (`PROVIDER_CLEARING:STRIPE:NGN` …) by a Flyway seed for every configured currency.
- Reversal: an entry with the opposite lines and `reverses_entry_id` pointing at the original. The UNIQUE constraint means an entry can only be reversed once.
- **Hot-row note.** `PROVIDER_CLEARING:STRIPE:NGN`, `PAYOUT_PAYABLE` and `TRANSFER_IN_FLIGHT` take part in many entries, so they'd serialise everything through one row lock.
  These system accounts are allowed to go negative, so they don't need a funds check. Their lock is taken last, and only to update the cached balance.
  The ADR covers this. If it becomes a bottleneck, the fix is to stop caching system balances and compute them from the lines.
- Reconciliation:
  - checks that `balance = Σ(signed lines)` for each account, and that Σ debits = Σ credits overall and per currency;
  - runs as a scheduled job (default hourly) plus `GET /api/v1/admin/ledger/reconciliation` (ADMIN);
  - also compares `accounts.balance` with the ledger while the expand phase lasts.
- Statement: `GET /api/v1/accounts/{accountNumber}/statement?page=&size=` (owner or ADMIN). The running balance is computed with a window function over that account's lines. It's paged by keyset on line id, and the page's opening balance comes from one SUM query.

### 3.3 Money rules
- A `Money` record holds a `BigDecimal` amount and a `Currency`. Each currency's scale comes from `Currency.getDefaultFractionDigits()`. The constructor rejects `amount.scale() > currencyScale` with a 400 (input is never rounded). It stores amounts at scale 4 in the DB and does arithmetic with HALF_EVEN only where it's unavoidable (fees, none yet). Comparisons use `compareTo` only, and an ArchUnit rule bans `BigDecimal.equals` in money packages.
- JSON: new DTOs use a `Money`/amount serializer that writes `"1500.00"` and accepts strings. For existing endpoints, see D3.
- ArchUnit: no `double`, `float`, `Double` or `Float` fields, parameters or return types in `..money..`, `..ledger..`, `..wallet..` or any DTO with an amount.
- `DECIMAL(19,4)` in every money column. The existing `DECIMAL(15,2)` columns are widened, which is expand-safe.

### 3.4 Flows and their journal entries (also in `docs/ledger.md`)

| Flow | Entry (idempotency key) |
|---|---|
| Opening-balance backfill (Flyway, once per existing account) | DR OPENING_BALANCE_EQUITY, CR wallet (`backfill-{accountId}`) |
| Account opened with `initialDeposit` (see D4) | DR OPENING_BALANCE_EQUITY, CR wallet (`acct-{id}-opening`) |
| Transfer started | DR sender wallet, CR TRANSFER_IN_FLIGHT (`tx-{id}-hold`) |
| Transfer completed | DR TRANSFER_IN_FLIGHT, CR receiver wallet (`tx-{id}-settle`) |
| Transfer rejected, OTP wrong or expired, or failure | DR TRANSFER_IN_FLIGHT, CR sender wallet (`tx-{id}-release`) |
| Admin credit (reason required) | DR ADJUSTMENT_EQUITY (adjustment), CR wallet (`adm-{uuid}` from Idempotency-Key) |
| Admin deduct (reason required) | DR wallet, CR ADJUSTMENT_EQUITY (`adm-…`). See D5 |
| Deposit succeeded (webhook) | DR PROVIDER_CLEARING:STRIPE:NGN, CR wallet (`dep-{id}-credit`) |
| Outbound payout hold (withdrawal or external transfer, see 8.3) | DR wallet (amount + fee), CR PAYOUT_PAYABLE (amount), CR FEE_REVENUE (fee, only if fee > 0) (`out-{id}-hold`) |
| Outbound payout settled (provider confirms success) | DR PAYOUT_PAYABLE, CR PROVIDER_CLEARING:{provider}:{currency} (`out-{id}-settle`) |
| Outbound payout failed, rejected, cancelled or expired before settle | DR PAYOUT_PAYABLE (amount), DR FEE_REVENUE (fee, if any), CR wallet (amount + fee) (`out-{id}-release`) |
| Outbound payout reversed by the bank after settle | DR PROVIDER_CLEARING:{provider}:{currency}, CR wallet (amount) (`out-{id}-reversal`). Fee refund: D20 |
| Inbound bank transfer received (see 8.6) | DR PROVIDER_CLEARING:{provider}:{currency}, CR wallet (`in-{providerReference}`) |

Note on PAYOUT_PAYABLE: a LIABILITY, money we owe the user until the provider confirms. It was called WITHDRAWAL_PAYABLE; nothing created it yet, so the rename costs nothing.

Note on PROVIDER_CLEARING (renamed from PAYOUT_CLEARING, which nothing created yet): it's an asset that takes a CR on settle, so it goes negative. That's money already paid out through the payout rail. It's zeroed when the provider statement is reconciled, which is out of scope. The rule is unchanged, and `ledger.md` will document it. Per-provider, per-currency naming is D22.

### 3.5 wallet-service (new, port 8087, `wallet_db`)
Tables: `deposit`, `outbound_payout` (each with `@Version`; holds withdrawals and external transfers, see 8.3), `outbound_daily_usage(account_id, day, total, version)`,
`pin_credential`, `pin_attempt`, `idempotency_record`, `outbox`, `processed_event`, `audit_log`.

**Deposits.**
1. `POST /api/v1/wallet/deposits` with an `Idempotency-Key` checks that the account is ACTIVE and owned by the caller, then inserts a PENDING deposit.
2. wallet-service calls payment-service at `POST /internal/v1/payments/intents` with a SERVICE token and the depositId as metadata. payment-service returns the client secret.
3. Stripe calls payment-service's webhook. payment-service verifies the signature and writes an outbox row `payment.intent.succeeded` / `payment.intent.failed` containing the Stripe event id, depositId, amount and currency.
4. wallet-service consumes it, deduplicating through `processed_event(stripe event id)`. It checks that the amount and currency match, posts the ledger entry `dep-{id}-credit`, marks the deposit COMPLETED and writes the outbox row `wallet.deposit.completed`.
5. A mismatch → FAILED + an audit row + an alert, and no credit.
6. An expiry job (default 24 h) cancels the PaymentIntent at Stripe first. It only marks the deposit EXPIRED if Stripe confirms the cancel, so a late success can't hit an EXPIRED deposit.

**Withdrawals.** The spec's steps, made safe across two databases:
1. **Tx A (wallet_db):**
   1. validate the request, check the account owner and that it's ACTIVE (ledger read API), and verify the PIN;
   2. check the per-transaction min/max;
   3. `SELECT … FOR UPDATE` the `outbound_daily_usage` row (insert it if missing) and check `total + amount ≤ limit`, then increment;
   4. insert the withdrawal REQUESTED and commit.
2. **Ledger HOLD:** `out-{id}-hold` (8.3). account-service locks the wallet row, checks the funds and posts. If funds are short, the withdrawal goes to FAILED, the daily usage is decremented, and the client gets a 422.
3. **Fraud check:** a synchronous `POST /internal/v1/fraud/check` (new). It **fails closed**: if the fraud service is unavailable, the withdrawal goes to PENDING_APPROVAL.
4. **Gate:** if the amount is below `WITHDRAWAL_APPROVAL_THRESHOLD` and fraud is clean → APPROVED → payout. Otherwise → PENDING_APPROVAL, plus the outbox event `approval-required`.
5. **Payout:** APPROVED → PROCESSING → `PayoutProvider.send()`, which runs in payment-service (8.7). Provider's confirmed success → settle → COMPLETED. Provider's confirmed failure → release → FAILED. The send call's own answer is not enough to settle (8.3).
6. A recovery job (every minute) picks up withdrawals stuck in REQUESTED, APPROVED or PROCESSING longer than N minutes and replays the next step. Every ledger call uses the deterministic key, so a replay is a no-op if the step already happened. The payout provider also gets `out-{id}` as its idempotency key (for withdrawals, `{id}` is the withdrawal id, as in fix 3).
7. One `WithdrawalStateMachine` holds an `EnumMap` of legal transitions. Every state change goes through it and writes an audit row and an outbox row in the same transaction.

**PIN.**
- The PIN is stored as `Argon2id(HMAC-SHA256(PIN_PEPPER, userId ‖ pin))`, using BouncyCastle via `spring-security-crypto`'s `Argon2PasswordEncoder`.
- Set, change and reset all require:
  1. `POST /api/v1/wallet/pin/otp` → an email OTP, stored **hashed** in Redis (5 min TTL, 5 attempts, single use);
  2. the current password, checked against auth-service's `POST /internal/v1/auth/verify-password` (SERVICE token, rate-limited, audited).
- Failed attempts are counted in MySQL (`pin_credential.failed_count, locked_until, lockout_level`). Five wrong attempts lock it for 30 min, and each later lockout is twice as long (cap 24 h).
- Logging masks `pin`, `otp`, `password` and `destination.accountNumber` with a Logback `MaskingPatternLayout` plus a request-logging filter. A test runs the flows with a known PIN and asserts that it never shows up in captured logs.

### 3.6 Idempotency (wallet, account admin endpoints, transaction-service)
- `idempotency_record(scope, idem_key, request_hash, status IN_PROGRESS|DONE, response_status, response_body, created_at)`, with UNIQUE(scope, idem_key). The scope is userId plus the route.
- Flow:
  1. `INSERT … IN_PROGRESS` in its own short transaction. A duplicate-key error means the key was seen before: return 409 if the stored row is in progress, 422 if the hash differs, otherwise replay the stored response.
  2. Run the handler.
  3. Store the response and mark the row DONE.
- Redis caches DONE records for 24 h as a fast path only. If Redis is flushed, the INSERT still collides, so nothing is charged twice. There's a test for this.
- Records older than 24 h are purged by a job, but **money is still protected after the purge**, because ledger keys are permanent.

### 3.7 Security
- account-service and wallet-service become OAuth2 resource servers with two decoders:
  - user tokens: HS256 `JWT_SECRET`, `iss=elibanking-auth`;
  - service tokens: HS256 `INTERNAL_JWT_SECRET`, `iss=elibanking-internal`, `role=SERVICE`, TTL 5 min, minted locally by each calling service.
- `/internal/**` requires `ROLE_SERVICE`. `/api/**` requires a user token. `@EnableMethodSecurity` + `@PreAuthorize` handle ADMIN and ownership checks.
- The gateway keeps doing its role checks (defence in depth) and still forwards the `Authorization` header (it does today). It never routes `/internal/**`.
- With no headers, a direct call to :8081 or :8087 → **401**. transaction, fraud and payment services switch their account-service calls to SERVICE tokens. Whether transaction and payment become resource servers too is D8.

### 3.8 Events: transactional outbox
Each service that publishes (account, wallet, payment, transaction) gets an `outbox` table that's written in the business transaction. A relay polls with `SELECT … FOR UPDATE SKIP LOCKED`, sends synchronously, then marks the row sent. That's at-least-once delivery, and consumers deduplicate through `processed_event(event_id)`. Envelope:
`{eventId, type, version: 1, occurredAt, correlationId, payload}`, with headers `X-Request-Id` and `eventId`. The topics are the ones the spec lists, plus `payment.intent.succeeded|failed` (payment → wallet) and `payment.payout.*`, `payment.inbound.*`, `payment.virtual_account.*` (8.2). Existing transfer topics keep their payload fields and gain `eventId`/`correlationId`, which is additive.

### 3.9 Audit
- Each service writes `audit_log` in its own DB, in the same transaction as the change. Append-only triggers protect it.
- Audited: account (ledger moves, block, admin credit/deduct), wallet (deposits, withdrawals, PIN events), transaction (status changes), auth (role, enable and disable changes).
- `GET /api/v1/admin/audit` → see D7.
- The gateway generates `X-Request-Id` if it's missing. A servlet filter puts it in the MDC, the Feign and RestClient interceptors forward it, and Kafka headers carry it in and out.

### 3.10 JPA, Flyway, Redis
- `open-in-view=false`, `ddl-auto=validate`, LAZY associations, and paging with DTO projections. A `QueryCountExtension` uses datasource-proxy and asserts query counts with 1 row and with 50 rows.
- Flyway: **V1 in every service reproduces today's Hibernate-generated schema** (taken from `SHOW CREATE TABLE`), with `baseline-on-migrate=true, baseline-version=1`. Existing databases are baselined, and fresh ones get V1. V2 and later are expand-only.
- Redis is used for rate limits, hashed OTPs, the idempotency fast path and the existing fraud heuristics. **If Redis is down:**
  - OTP verify/issue → 503 (fail closed);
  - wallet and PIN rate limits → **fail closed (503)** for withdrawals, PIN and OTP, because the MySQL lockout still protects the PIN; but **fail open** for read endpoints;
  - the gateway's limiter → stays at Spring's default (fail open), documented;
  - idempotency → MySQL only, which is slower but still correct.

---

## 4. Changes per service

| Service | Changes |
|---|---|
| **account-service** | Flyway V1 baseline; V2 ledger tables, triggers and system accounts; V3 opening-balance backfill; V4 `audit_log`/`outbox`/`processed_event`/`idempotency_record`. `Money`, `LedgerPostingService`, internal ledger API (post, get by key, reverse, account info), statement, reconciliation job and endpoint, admin credit/deduct with reason, resource server, outbox relay. `accounts.balance` is still written in the same transaction (expand) so the old version and the README keep working. Remove the `transaction.completed` credit consumer, because the receiver is now credited by `tx-{id}-settle`. |
| **transaction-service** | Flyway. The saga moves to ledger hold/settle/release. The `Transaction` row and an outbox `transaction.initiated` are written **before** the hold, and a recovery job drives stuck rows. `@Version` on `Transaction` (fixes the verify race). Hashed OTP, `SecureRandom`. OTP events only on `notification.otp`, never on transfer topics (D6). `Idempotency-Key` on `POST /transactions`. SERVICE token for account calls. PIN required whenever `TRANSFER_PIN_REQUIRED=true` (D2, review fix 1). |
| **fraud-detection-service** | New internal `POST /internal/v1/fraud/check` (SERVICE token), with the same rules plus a withdrawal profile. SERVICE token for the balance read. Kafka path unchanged. |
| **payment-service** | Flyway. Internal `POST /internal/v1/payments/intents` (depositId and currency in metadata). The webhook writes outbox events with the Stripe event id, deduplicates through `processed_event`, and no longer swallows errors (it returns 500 so Stripe retries). The old `/create-order` keeps working (D9). It also owns every provider adapter, the Paystack webhook and the payout and virtual-account APIs (8.2). |
| **wallet-service (new)** | Everything in 3.5 to 3.7: deposits, withdrawals, PIN, name enquiry, bank list, the outbound pipeline (8.3–8.5), jobs, audit, outbox, idempotency. The payout simulator moved to payment-service (8.2). |
| **auth-service** | Flyway. `POST /internal/v1/auth/verify-password` (SERVICE token, rate-limited, audited). `audit_log` for role and enable changes. Publishes `auth.pin-otp.requested`, or wallet does it (wallet owns the OTP). |
| **notification-service** | Consumers for the wallet/ledger topics, deduplicated through `processed_event` (needs a small `notification_db` and Flyway). |
| **API-GATEWAY** | `/api/v1/wallet/**` → wallet. `/api/v1/admin/ledger/**` → account. `/api/v1/admin/audit` → D7. `X-Request-Id`. Wallet docs in the combined Swagger UI. Never routes `/internal/**`. Per-user rate limits for wallet POSTs. |
| **Infra** | Multi-stage Dockerfiles with the build context at `bankend/` if D1 = shared lib. Healthchecks on all containers (Java-free check through bash `/dev/tcp`, or add curl to the image). Pin `mysql:9.7.4` (current LTS; Testcontainers uses the same tag, see section 0), Redis and Kafka versions. Per-service MySQL users (D10). Add wallet to `compose.prod.yaml`. `.env.example`: every variable in 8.10, plus `INTERNAL_JWT_SECRET`, `PIN_PEPPER`, `WITHDRAWAL_*`, `DEPOSIT_EXPIRY`. |
| **CI/CD** | `.github/workflows/` at the **git root** (one level above `bankend/`): `ci.yml` (build, unit, Testcontainers, ArchUnit, compose up + smoke), `release.yml` (GHCR images tagged with the SHA → staging → staging rollback drill → prod behind environment approval), `rollback.yml`. `docs/deploy.md` lists every secret and server step. |

## 5. Phases (each ends green: build + all tests + commit)

1. **Foundations.** Testcontainers base in every service (existing `contextLoads` moves to it). Flyway V1 baselines. `validate` + `open-in-view=false`. Multi-stage Dockerfiles, healthchecks, pinned images. Shared lib skeleton (D1). ArchUnit money rule. CI `ci.yml`.
2. **Ledger core (account-service).** Money, ledger tables and triggers, posting with locks, reversals, reconciliation, statement, backfill and its test, audit, outbox, idempotency, resource server with SERVICE tokens, admin credit/deduct with reason. ADR `0001-locking.md` and `ledger.md`. Concurrency tests (50 parallel, opposite transfers).
3. **Transfers onto the ledger.** transaction-service changes from section 4. Update the smoke test and Postman. All 107 existing checks still pass, adapted only where the contract was deliberately changed (Idempotency-Key, reason), and each change is listed in the commit.
4. **wallet-service and PIN.** `threat-model-pin.md` **first**, then PIN, the auth verify-password endpoint, OTP, lockout, log-masking test.
5. **Deposits.** payment-service internal intent, webhook outbox, wallet consumer, expiry, tests (replay, bad signature, mismatch). The smoke test signs webhook payloads itself with `STRIPE_WEBHOOK_SECRET` via openssl, so no Stripe CLI is needed. **Starts only after D26 and D27 are answered** (8.8, 8.16). The wallet is credited the requested amount, never more and never less (review fix 3).
6. **Withdrawals and the outbound pipeline.** State machine (with REVERSED), limits, fraud endpoint, gate, maker-checker, expiry, recovery, and the `PayoutProvider` interface with its simulator, which lives in payment-service (8.2, 8.3). The pipeline is built for both types from the start (`type = WITHDRAWAL` now), so 6b adds a type and does not add a second pipeline. All transition and concurrency tests.
6b. **External transfers.** Bank list, name enquiry, routing in transaction-service, `EXTERNAL_TRANSFER` on the phase-6 pipeline, Paystack adapter (recipient, transfer, verify by reference), signed webhooks for transfer.success / transfer.failed / transfer.reversed with dedupe and matching, reversal entries. `docs/deploy.md` is created here with every variable so far. Smoke test: external transfer and withdrawal on the simulator, plus a sandbox run for the happy path only (8.7). Needs your Paystack test secret key in `.env` (gitignored).
6c. **Inbound bank transfers.** `POST /api/v1/wallet/virtual-account`, Paystack dedicated account (customer, then assign, async webhook), `charge.success` with channel `dedicated_nuban` → outbox → wallet credits once, the dev-only simulator, and a smoke test for a deposit by bank transfer on the simulator. Starts after gate G2 (8.8). If G2 fails, 6c ships with the simulator only and the Paystack adapter waits.
7. **Events and audit.** `events.md`, notification consumers, `X-Request-Id` end to end, `/admin/audit`.
8. **Proof layer.** Query-count tests for every list/detail endpoint, property test (jqwik, thousands of random operations), Flyway "empty → latest" and "data → latest" tests, Redis-flush idempotency test, security matrix tests.
9. **Packaging and docs.** Gateway, Swagger, compose, `compose.prod.yaml`, OpenAPI JSON files, Postman, README.
10. **CD.** `release.yml`, `rollback.yml`, `deploy.md`.

This is a large piece of work, roughly comparable to the existing codebase several times over. I'll report at the end of each phase.

---

## 6. Conflicts between the spec and the code: decisions needed

The **recommended** option is the default. If you say "go" without comments, I'll use these.

| # | Conflict | Recommendation |
|---|---|---|
| **D1** | The spec needs the same idempotency, outbox, audit, `Money`, internal-JWT and MDC code in 4-5 services. The repo has no shared module, and each service builds on its own. | Add `bankend/libs/banking-common` (a plain Maven jar). Each service's `mvnw` installs it through a `-f` reactor, and the Docker build context becomes `bankend/`. The alternative is copying about 1.5k lines into each service. |
| **D2** | PIN on transfers. It fits cleanly: transaction-service calls wallet `POST /internal/v1/pin/verify`. But existing users have no PIN, and clients would break. | Add a `pin` field to `POST /transactions`. `TRANSFER_PIN_REQUIRED=true` by default. A user without a PIN gets `403 "Set a PIN first"`. The smoke test sets a PIN before it transfers. |
| **D3** | "Amounts in JSON as strings" vs existing responses that return numbers (`/balance` returns `9000.00`, and the smoke test asserts numbers). | **All** money fields in all responses become strings. Inputs accept both, but a number input with too many decimals still gets a 400. This is a breaking change for any existing frontend; there isn't one yet (the frontend is the Vite template). The smoke test changes to `.=="500.00"`, which is the same assertion and not a weaker one. |
| **D4** | `initialDeposit` on account opening creates money from nothing. | Keep it for now as an `OPENING_BALANCE_EQUITY` entry behind `ACCOUNT_OPENING_DEPOSIT_ENABLED` (true in dev, **false in prod**: accounts open at 0 and are funded by a deposit). |
| **D5** | Admin credit/deduct "against a system account": the spec doesn't name the counter-account. | **Approved 2026-11-03:** one `ADJUSTMENT_EQUITY` system account (a 7th seed) is the counter-account for both credit (DR `ADJUSTMENT_EQUITY`, CR wallet) and deduct (DR wallet, CR `ADJUSTMENT_EQUITY`). `FEE_REVENUE` is not used for admin adjustments. `reason` is required as a query param (it stays `PUT … ?amount=&reason=`), and the response amounts are strings. |
| **D6** | Transfer OTP today: plaintext, included in the Kafka payload, logged on purpose in dev, one wrong attempt blocks the account. The spec (§11) wants hashed OTPs, single use, max attempts. | **Approved 2026-11-03:** hash the OTP in Redis and allow 3 attempts. The 3rd wrong one keeps today's behaviour (FLAGGED + refund + block). OTP events go on their own topic, `notification.otp` (retention 1 hour, `retention.ms=3600000`), never on the transfer topics. `transaction.otp.generated` is retired: it gets no new events, and its consumer is kept for one release (expand/contract). `MAIL_LOG_CONTENT=false` in prod (already the case). |
| **D7** | "Audit in the same transaction" means every service keeps its own `audit_log`, but `/admin/audit` is a single endpoint. | Each service serves `GET /internal/v1/audit`. wallet-service's `/api/v1/admin/audit` fans out, merges by timestamp and pages with a cursor. The alternative is a Kafka-fed read copy, which is more moving parts. |
| **D8** | §10 only requires wallet and account to validate JWTs. transaction and payment would still trust headers, and :8082/:8085 would stay open in dev. | Make **transaction and payment resource servers too**, with the same small config from the shared lib, since they move money indirectly. This keeps the "direct port without JWT → 401" test honest for all money services. |
| **D9** | `POST /api/v1/payments/create-order` stays, but it credits nothing today, and deposits replace it. | Keep it unchanged and mark it deprecated in OpenAPI and the README. Deposits are the funding path. Removing it is a later contract step. |
| **D10** | "No other service may change a balance": every service connects as MySQL root, and root can also `DROP TRIGGER`. | Per-service MySQL users from an init script. Each gets only its own schema, and the app users have no TRIGGER/ALTER rights. Flyway runs as a separate `*_migrator` user, and only account_db's migrator can create triggers. Dev keeps `root` available for debugging. |
| **D11** | Accounts are addressed by `accountNumber` everywhere, but the spec writes `{id}` and `accountId`. | Use `accountNumber` in paths and bodies (`/accounts/{accountNumber}/statement`, `"accountNumber"` in the withdrawal body), as today. **Changed by D23 (8.13) for the transfer and withdrawal bodies:** they keep `senderAccountNumber` / `receiverAccountNumber`. |
| **D12** | There's no currency anywhere, and Stripe is hard-coded to USD. | **Resolved: NGN** (2026-11-04). Add `currency` to accounts, defaulting to `NGN` and backfilled as NGN. One currency per wallet. A transfer across currencies → 422. `LEDGER_CURRENCIES=NGN` seeds the system accounts. Stripe charges in NGN only after gate G1 (8.8). |
| **D13** | Daily withdrawal limit: config vs the existing `accounts.dailyTransactionLimit` (100k / 5M). | Use `min(WITHDRAWAL_DAILY_MAX, account.dailyTransactionLimit)`. Day boundaries are in UTC. |
| **D14** | Paging "on every list" would turn today's JSON arrays into page objects, which is a breaking change. | Existing list endpoints keep returning arrays and gain `?page=&size=` (default 50, max 200) plus `X-Total-Count`. New endpoints return `{content, page, size, totalElements}`. |
| **D15** | "Expand/contract: the previous version must still run on the new schema." The schema can be compatible, but the old account-service code would write `accounts.balance` without the ledger, and the old transaction-service would credit through Kafka. | The schema stays backward compatible (no drops or renames, new columns are nullable or have defaults). Rolling back to the old version **while money moves** would let balances drift from the ledger. Reconciliation catches it, and `deploy.md`'s rollback runbook says: rolling back across the ledger release = pause money endpoints, then roll back, then reconcile. All rollbacks after that release are safe both ways. |
| **D16** | §14 asks for deploy pipelines, but you have no servers. | Write every workflow for real. Staging and prod jobs skip with a clear notice when `STAGING_HOST` / `PROD_HOST` aren't set, so CI stays green. The rollback drill runs once staging exists. **It can't be proven green in the pipeline until you add a host.** |
| **D17** | §7 asks for query-count tests on "every list/detail endpoint". | All endpoints in account, wallet and transaction (the money services), plus auth's `/users`. payment and notification have no list endpoints. |

## 7. Risks

- **Cross-DB consistency.** The ledger (account_db) and wallet and transaction state live in different databases, and there's no 2PC. This relies on deterministic ledger keys, the outbox, recovery jobs and reconciliation. Each is tested, but what's guaranteed is eventual consistency of *state*. Money is atomic, because each ledger entry is one local transaction.
- **Hot system-account rows** under load (3.2). They're measured in the concurrency test.
- **MySQL trigger privileges** with binlog on. This needs `log_bin_trust_function_creators=1` for the non-root migrator, set in compose and Testcontainers.
- **Lock timeout:** `innodb_lock_wait_timeout` is 50 s by default. It gets set to 5 s per connection through Hikari `connection-init-sql`. A timeout → 503 with retry advice; the client retries with the same Idempotency-Key.
- **Build time:** Testcontainers MySQL, Kafka and Redis in every service. CI uses a cached Maven repo and reuses containers per JVM.
- **Breaking API changes** (D2, D3, D5, the Idempotency-Key requirement). They're listed in the README under "Breaking changes", with the Postman collection and smoke test updated in the same commits.
- **Existing dev data:** 17 accounts are backfilled. The backfill test runs against a copy of today's schema with seeded rows.
- **Scope:** this is weeks of normal engineering. I'll keep each phase shippable, so stopping after any phase leaves a working, better system.

---

## 8. Scope change (2026-11-04): external transfers, bank deposits, withdrawal to own bank

**Status: proposed, awaiting your approval. Nothing here is built.** Gates G1 and G2 need your provider accounts (8.8). Decisions are in 8.13.

### 8.1 What changed and what didn't

The new requirements: (1) deposit by card (Stripe) and by bank transfer from any Nigerian bank; (2) transfer to any account number, ours or another bank; (3) withdraw to the user's own account at another bank. All accounts are NGN (D12).

Edited in place in sections 0–6, because the old text would be wrong otherwise:
- Section 0 and D12: resolved to NGN.
- 3.2, 3.4 and 3.5: `STRIPE_CLEARING` became `PROVIDER_CLEARING:STRIPE:NGN` (D22); admin rows use `ADJUSTMENT_EQUITY` (D5); `WITHDRAWAL_PAYABLE` → `PAYOUT_PAYABLE`; `PAYOUT_CLEARING` → `PROVIDER_CLEARING:{provider}:{currency}`; withdrawal keys `wd-{id}-*` → `out-{id}-*`; new rows for external transfers, reversals and inbound transfers.
- 3.5 steps 2, 5 and 6: the key prefix, and "success" in step 5 now means the provider's confirmed success (8.3).
- Section 4, payment-service and wallet-service rows: the payout simulator moved to payment-service.
- Section 5: phase 6 reworded; phases 6b and 6c inserted after it.
- Section 6: D5 and D6 rewritten to the approved text (review clean-up 6); D11 changed by D23; D12 resolved.

Fixes 1–5 are unchanged, and their text is not edited. Where the new scope needs a fix to cover another case, it's listed as a decision: fix 5 for REVERSED (D21), and fix 4 for the new calls (8.2).

### 8.2 Ownership

| Service | Owns in this scope | Calls |
|---|---|---|
| transaction-service 8082 | `POST /api/v1/transactions`: routes between the internal saga and the outbound pipeline. The internal saga itself is unchanged. | wallet: `POST /internal/v1/name-enquiries/validate` (any request with a `bankCode`); `POST /internal/v1/outbound-transfers` (other banks) |
| wallet-service 8087 | `GET /api/v1/banks`; `POST /api/v1/transfers/name-enquiry` (table `name_enquiry`); `POST /api/v1/wallet/withdrawals`; `GET /api/v1/wallet/payouts/{id}`; the outbound pipeline (table `outbound_payout` with a `type` column); PIN; virtual accounts (6c) | account-service (own-bank lookup, ledger); payment-service (banks, resolve, payout send and status, virtual accounts); fraud-detection |
| payment-service 8085 | every provider adapter: `PayoutProvider` (simulated, Paystack), `VirtualAccountProvider`, Stripe; the Paystack webhook; outbox events `payment.payout.*`, `payment.inbound.*`, `payment.virtual_account.*`; the dev-only inbound simulator | Paystack and Stripe APIs |
| account-service 8081 | the ledger, as before. New system accounts: `PAYOUT_PAYABLE` and `PROVIDER_CLEARING:PAYSTACK:NGN` (D22) | none new |

Dependency direction: transaction → wallet → payment, account, fraud. Nothing calls back into transaction-service, so there's no cycle.

**Why the adapters stay in payment-service** (you asked me to argue it):
- **Secrets.** One service holds `STRIPE_SECRET_KEY` and `PAYSTACK_SECRET_KEY`. Wallet never sees a provider key.
- **Webhooks.** payment-service already verifies Stripe webhooks and writes outbox events (phase 5). Paystack's webhooks, for payouts and inbound transfers, go to the same place, with one signature check and one dedupe table.
- **Testability.** Wallet stays provider-free, so its tests run against the interface with the simulator.
- **Cost.** Every send and status check is one more hop, and payout results reach wallet through the outbox, which is asynchronous. The recovery job calls `getStatus` before any resend (fix 3), so a payment-service outage delays payouts but can't pay twice. I think that's worth it.

**Why name enquiry lives in wallet.** The ref is checked against the pipeline's own record, so withdrawals and external transfers use one validation in one place. Internal transfers cost one extra internal call. The alternative, transaction-service owning it, makes withdrawals call back into transaction-service.

**Fix 4 allow-list additions** (each gets a test: wrong caller → 403):
- `name-enquiries/validate` and `outbound-transfers`: transaction-service only.
- payment-service's `banks`, `accounts/resolve`, `payouts` and `virtual-accounts`: wallet-service only.
- `sim/inbound-transfer`: the smoke test only, and the endpoint doesn't exist unless `INBOUND_SIMULATOR_ENABLED=true`.

### 8.3 One outbound pipeline (withdrawal and external transfer)

- **One table.** `outbound_payout` holds both types (`type = WITHDRAWAL | EXTERNAL_TRANSFER`). Renamed from `withdrawal` by review clean-up 5, and `withdrawal_daily_usage` became `outbound_daily_usage` (the same counter for both types). Fixes 2 and 5 use the new names and keep their meaning.
- **Payout key `out-{id}`**, where `{id}` is the row id. It's the ledger key prefix, the provider idempotency key and Paystack's `reference`.
- **State machine** (one `EnumMap`, as in 3.5). Added: `REVERSED`, reached only when a `getStatus` answer says REVERSED, in two cases (review fix 2):
  - `PROCESSING → REVERSED`: the release entry only (`out-{id}-release`). Nothing was settled, so there's no settle and no reversal entry. Paystack can reverse a transfer that never reached success.
  - `COMPLETED → REVERSED`: the reversal entry (`out-{id}-reversal`).
  Each transition writes the audit row and the outbox event `wallet.payout.<state>` in the same transaction. Every transition into REJECTED, CANCELLED, EXPIRED, FAILED or REVERSED also decrements `outbound_daily_usage` (fix 5, D21).
- **Settle waits for a status answer.** Settle happens only when `getStatus` says SUCCESS for a PROCESSING payout, never on the return value of `send` (Paystack can answer `pending`). If the `out-{id}-settle` key already exists, the settle is a no-op. That covers a success that arrives after recovery has already settled.
- **Ledger.** The 3.4 rows. Fee: `OUTBOUND_FEE`, a flat NGN amount, default 0, same for both types. If it's above 0, the hold credits FEE_REVENUE, the release refunds it, and a success keeps it. Refund on bank reversal: D20.
- **Limits.** `WITHDRAWAL_MIN/MAX` and `WITHDRAWAL_DAILY_MAX` apply to both types, with D13's `min(WITHDRAWAL_DAILY_MAX, account.dailyTransactionLimit)`. The daily counter is shared, so external transfers use up withdrawal allowance. The spec says "same limits", so this is intended.
- **Gate and fraud.** Identical. The fraud request carries `type`, so the rules can differ later. v1 uses the same rules.
- **PIN.** Wallet verifies the PIN inside Tx A for both types, so there's one lockout counter. For external transfers, transaction-service forwards the PIN and doesn't check it.
- **Recovery.** Unchanged from 3.5 and fix 3. PROCESSING asks `getStatus` first: NOT_FOUND → resend with the same key; SUCCESS → settle; FAILED → release; REVERSED → release only (review fix 2); PENDING → wait. Stuck REQUESTED or APPROVED → replay, as before.
- **Webhooks are triggers, not the truth (review fix 2).** Any `payment.payout.*` event makes wallet call `getStatus(key)` and act on that answer. The event's own status is never used. Late, out-of-order and duplicate events only trigger another check, and the ledger keys make a repeat a no-op. The status answer must match our row's reference, amount and currency. If it doesn't: no action, and an alert.
- **Withdrawal endpoint.** `POST /api/v1/wallet/withdrawals` takes the same body as an external transfer (8.5). The destination is checked against `fullName` (D19, a label, not a control). A destination with `bankCode = OWN_BANK_CODE` → 422 ("use a transfer").

### 8.4 Bank list and name enquiry (wallet-service)

- **`GET /api/v1/banks`** → `[{name, code}]`. Source: payment-service `GET /internal/v1/banks`, cached in Redis for `BANK_LIST_CACHE_TTL` (24 h). Wallet adds our own bank (`OWN_BANK_NAME`, `OWN_BANK_CODE`). If Redis is down, read straight from the provider (a read endpoint, so fail open, per 3.10).
- **`POST /api/v1/transfers/name-enquiry {bankCode, accountNumber}`**
  - `bankCode = OWN_BANK_CODE` → account-service account lookup. A name is returned only for ACTIVE accounts; anything else → 422.
  - Any other code → payment-service `POST /internal/v1/accounts/resolve`.
  - Stores `name_enquiry(ref, user_id, bank_code, account_number, account_name, expires_at = now + NAME_ENQUIRY_TTL)`. The ref is 128 random bits, opaque.
  - Returns `{nameEnquiryRef, accountName, expiresAt}`.
- **Rate limit.** `NAME_ENQUIRY_RATE_LIMIT` per user per 10 minutes, in Redis. **It fails closed (503)**, unlike other read endpoints, because each call costs a provider request and this limit is the control against harvesting names.
- **Validation** (one function, used by withdrawals in wallet and by transaction-service through the internal endpoint). The ref must exist, belong to the caller, be unexpired, and match `bank_code` and `account_number`. Any failure → 422 with one message ("Name enquiry is invalid or expired"), so the response doesn't reveal which check failed.
- **Reusable until expiry.** A transfer doesn't consume the ref, so an idempotent retry with the same key still works. The spec doesn't say single-use, and single-use would break retries.
- **Enumeration.** A transfer to a number that doesn't exist returns an error, which reveals existence. The per-user limit on `POST /api/v1/transactions` (section 4) covers this.
- `accountNumber` is masked in logs, as in 3.5.

### 8.5 Routing: `POST /api/v1/transactions`

**Request.** The existing body (`senderAccountNumber`, `receiverAccountNumber`, `amount`, `description`, `type`) plus `bankCode`, `pin` and `nameEnquiryRef` (D23), and the `Idempotency-Key` header.

**Routing, in order:**
1. **No `bankCode`** (= our bank) → the legacy internal saga. The ref is optional. The PIN is required whenever `TRANSFER_PIN_REQUIRED=true` (review fix 1). A wrong PIN counts toward the lockout, because the check runs in wallet (`POST /internal/v1/pin/verify`).
2. **`bankCode = OWN_BANK_CODE`** → same as step 1. If a ref is sent, validate it (wallet) and check that it matches `receiverAccountNumber`.
3. **Any other `bankCode`** → validate the ref (required here) → forward to wallet `POST /internal/v1/outbound-transfers` with the user id, the Idempotency-Key, the validated account name, the PIN and the amount. Returns `202` with the payout id and state. The final result comes later: a payout event triggers a status check (8.3), and the result is read from `GET /api/v1/wallet/payouts/{id}`.

**Two idempotency layers.** transaction-service's `idempotency_record` (3.6) wraps the request. Wallet's `UNIQUE(user_id, idempotency_key)` on `outbound_payout` makes the forward safe on its own: a retry after a crash returns the same payout. The same key with a different body → 422 at both layers.

### 8.6 Inbound bank transfers (phase 6c)

- **Virtual account.** `POST /api/v1/wallet/virtual-account` with `{consent: true}`, once per wallet (unique index). Wallet calls payment-service `POST /internal/v1/virtual-accounts`. payment-service creates the Paystack customer once (storing `provider_customer_code`), then assigns the dedicated account. Assignment is **asynchronous**: the row stays PENDING until `dedicatedaccount.assign.success` arrives, then the outbox event `payment.virtual_account.assigned` sets it ACTIVE and stores the number. `customeridentification.failed` or `assigndedicatedaccount.failed` → FAILED with the reason, and the user can retry.
- **Customer data.** Name, email and phone (D24).
- **Credit.** payment-service receives `charge.success` with channel `dedicated_nuban`, verifies the signature (8.7), dedupes on the event (`processed_event`), finds the virtual account by number and writes the outbox event `payment.inbound.received {providerReference, amount, currency, accountNumber}`. Wallet consumes it once, through `processed_event` and the ledger key `in-{providerReference}`: the account must be ACTIVE and the currency NGN, and the amount must be > 0. Otherwise no credit and an alert. An unknown account number → no credit, and the row goes to `unmatched_inbound` for manual review. The money is at Paystack, so this is for review, not an automatic refund.
- **Verify before credit** (D25). Before crediting, call Paystack's transaction verify endpoint for the reference and check status and amount. The signed webhook is still the trigger; the verify call is a second check.
- **Simulator.** `POST /internal/v1/sim/inbound-transfer` in payment-service. It exists only if `INBOUND_SIMULATOR_ENABLED=true`, and startup fails if that's `true` under the `prod` profile. It writes the same outbox event as a verified webhook, through the same code path after the signature step. The smoke test calls it with a SERVICE token that it signs with openssl from `INTERNAL_JWT_SECRET` (dev only).

### 8.7 Provider adapters (payment-service)

**Interface `PayoutProvider`:**
- `listBanks() → [{name, code}]`
- `resolveAccount(bankCode, accountNumber) → accountName | NOT_FOUND | UNAVAILABLE`
- `send(key, Money amount, bankCode, accountNumber, accountName, narration) → {providerRef, status: ACCEPTED | PENDING | FAILED}`
- `getStatus(key) → SUCCESS | PENDING | FAILED | REVERSED | NOT_FOUND`

**Internal APIs** (SERVICE token, wallet only): `GET /internal/v1/banks`, `POST /internal/v1/accounts/resolve`, `POST /internal/v1/payouts` (send, keyed by `key`), `GET /internal/v1/payouts/{key}` (getStatus), `POST /internal/v1/virtual-accounts`.

**`SimulatedPayoutProvider`** (default: `PAYOUT_PROVIDER=simulator`, for dev, tests and CI):
- Keeps sends by key. A repeated send with the same key returns the first result. This is what fix 3's test relies on: crash after send, recovery runs, exactly one payout.
- In tests, the outcome comes from the **last digit of the account number**, so tests are deterministic: 0 → FAILED; 1 → PENDING until `getStatus` is called; 2 → SUCCESS, then REVERSED at the next status check; anything else → SUCCESS. `PAYOUT_SIM_DEFAULT_OUTCOME` and `PAYOUT_SIM_DELAY_MS` apply to other numbers in dev.
- Unknown keys → NOT_FOUND.

**`PaystackPayoutProvider`** (`PAYOUT_PROVIDER=paystack`). The endpoints are from Paystack's docs and must be confirmed in 6b:
- `listBanks`: `GET /bank?currency=NGN`.
- `resolveAccount`: `GET /bank/resolve?account_number=&bank_code=`.
- `send`: `POST /transferrecipient` (type nuban, reused per account pair), then `POST /transfer` with `source: balance`, `amount` in kobo (an integer; the adapter converts from `Money` and rejects fractional kobo), `recipient`, `reference = key`, `reason = narration`. A crash between the two calls is safe, because `getStatus` looks up by reference.
- `getStatus`: `GET /transfer/verify/{reference}`. Paystack's HTTP status reflects the API call, not the transfer, so the adapter maps the transfer's own `status` field.
- **Webhook** `POST /webhooks/paystack` (public, signature-checked). `x-paystack-signature` must equal the hex HMAC-SHA512 of the raw body keyed with `PAYSTACK_SECRET_KEY`. The comparison is constant-time, and a mismatch → 400. Dedupe on `processed_event` keyed by event and `data.reference`. Handled: `transfer.success`, `transfer.failed`, `transfer.reversed` (payouts), plus the inbound and virtual-account events from 8.6. payment-service returns 200 once the outbox row is written. It returns 500 only if that write fails, so Paystack retries. Wallet's processing never changes the webhook response (review fix 2, 8.3).
- **Matching.** payment-service emits `payment.payout.<status>` as a trigger, carrying the reference. Wallet then calls `getStatus(key)` (8.3) and checks the reference, amount and currency in the answer against its own row before acting. A mismatch → no action, and an alert.
- **Keys.** The secret key is also the webhook HMAC key, so there's no separate webhook secret. The dev and test profiles refuse `sk_live_` keys.
- **Sandbox limits.** A search reports that Paystack test-mode transfers always succeed, which means the sandbox can't exercise failure or reversal. Those paths are tested with the simulator, and with recorded `transfer.failed` and `transfer.reversed` fixtures pushed through the same handler. The sandbox run proves auth, bank list, resolve, transfer creation, the signed webhook and the happy path. Unknown until 6b: whether our account needs transfer OTP finalisation. If it does, that's an extra step, and I'll raise it before building.

Stripe is unchanged.

### 8.8 Gates before phases start

- **G1, before phase 5: Stripe NGN. Run 2026-11-04** (test mode, our `sk_test_` key from `.env`; test objects only, no real money):
  - **Accepted for card.** PaymentIntent `pi_3UY2IA4SPWwG6i3U0hGNgYNF`, `currency: ngn`, `amount: 100000` (1,000.00 NGN in kobo). Confirmed with the test card `pm_card_visa` → `succeeded`. `amount_received: 100000`. Charge `ch_3UY2IA4SPWwG6i3U0JVbONr3`: amount 100000, captured 100000, refunded 0. **Nothing was added on top.** The test card's country is US, so the VAT path for Nigerian buyers wasn't exercised.
  - **Settles in USD.** Balance transaction `txn_3UY2IA4SPWwG6i3U0bVXGz8d`: gross 71 USD cents, fee 33 (1 currency conversion, 32 processing), net 38, exchange rate 0.000710374 NGN→USD, status pending. The processing fee is a fixed 32 cents, so on a small deposit it takes most of the value. This is D27.
  - **`ng_card` rejected.** Stripe answered `payment method type "ng_card" is invalid` and asked us to activate it in the dashboard. So the Nigerian card path, where Stripe's NGN page says a merchant-of-record adds VAT, isn't enabled on our account.
  - **Account state.** Country US, default currency usd, `charges_enabled: false`, `payouts_enabled: false`. Test charges worked. Live charges won't until Stripe onboarding is finished.
  - **Amount rule (review fix 3, proposed).** Credit exactly the requested amount, never more and never less. Credit only if `amount_received` ≥ the requested amount. Anything above it (VAT or any other add-on) is not credited; what happens to it is D26. `amount_received` below the requested amount → FAILED, no credit, alert. **Phase 5 doesn't start until you answer D26 and D27.**
  - Stripe's NGN page says naira payments go through a merchant-of-record partner and include VAT for Nigerian buyers. That path is `ng_card`, which isn't enabled here, so the amount rule above must still cover it before it's enabled.
- **G2, before 6c: Paystack virtual accounts.** A search reports that the business must be enabled for the feature (by emailing Paystack support), that creating a customer needs the customer's consent, and that test mode uses `test-bank` as the preferred bank. I couldn't confirm any of this for our account. **Test:** create a customer and assign a dedicated account on our test key. Passes → 6c ships with Paystack. Fails → 6c ships with the simulator only, and the Paystack adapter waits.

### 8.9 Tests (added)

- **Routing and PIN (6b, review fix 1):** no `bankCode` and no PIN → 400. No `bankCode` and a wrong PIN → 403, and the failure counts toward the lockout. No `bankCode` with a valid ref, or without one → both accepted. Own code → internal saga. Other code without a ref → 422. Other code with a ref → outbound pipeline.
- **Webhooks (6b, review fix 2):** a REVERSED answer while PROCESSING → release only, usage given back, no settle. Success arriving after recovery already settled → no-op. The same event twice → one net effect. (Tests use `getStatus` answers, not the event's own status.)
- **Name enquiry (6b):** expired, another user's, or mismatched `bankCode`/`accountNumber` → 422. Rate limit → 429. Redis down → 503 (fails closed).
- **External transfer (6b):** success; failure → release, with the fee refunded too; reversal after success → refund (fee per D20); webhook replayed → applied once; bad signature → 400; amount or currency mismatch → rejected, no state change, alert.
- **Withdrawal (6b):** destination name not matching `fullName` → 422 (D19). Own bank code → 422.
- **Inbound (6c):** replayed webhook → credited once; unknown account → no credit, row in `unmatched_inbound`; currency mismatch → no credit; simulator endpoint returns 404 when the flag is off.
- **Concurrency (6b, extends phase 6):** parallel internal transfers, external transfers and withdrawals on one wallet → balance never negative, ledger balanced (reconciliation passes), one provider send per key.
- **Smoke test (6b, 6c):** card deposit (phase 5), bank deposit (simulator), internal transfer, external transfer, withdrawal, all on the simulator.
- **Fixes:** the tests in section 0 still apply to the new flows.

### 8.10 Environment variables

Each variable goes into `.env.example` and into `docs/deploy.md` in the phase that adds it. `docs/deploy.md` doesn't exist yet. Phase 10 was going to create it; I'd create it in 6b, so the list exists from the first phase that adds a variable.

| Variable | Service | Default | Notes |
|---|---|---|---|
| `PAYOUT_PROVIDER` | payment | `simulator` | `simulator` or `paystack` |
| `PAYSTACK_SECRET_KEY` | payment | none | Also the webhook HMAC key. `sk_test_` only outside prod. |
| `PAYSTACK_BASE_URL` | payment | `https://api.paystack.co` | Tests point it at a stub server. |
| `PAYOUT_SIM_DEFAULT_OUTCOME` | payment | `success` | `success`, `failure` or `pending`. Tests use the account-digit rule (8.7). |
| `PAYOUT_SIM_DELAY_MS` | payment | `0` | Simulated latency. |
| `VIRTUAL_ACCOUNT_PROVIDER` | payment | `simulator` | `simulator` or `paystack` (6c). |
| `PAYSTACK_DVA_PREFERRED_BANK` | payment | none | Value confirmed in the sandbox (6c). |
| `INBOUND_SIMULATOR_ENABLED` | payment | `false` | Dev only. Startup fails under `prod` if `true`. |
| `OWN_BANK_CODE` | wallet, transaction | none, required | Our code in the bank list. |
| `OWN_BANK_NAME` | wallet | none, required | Shown in the bank list. |
| `NAME_ENQUIRY_TTL` | wallet | `PT10M` | |
| `NAME_ENQUIRY_RATE_LIMIT` | wallet | `20` | Per user per 10 minutes. |
| `BANK_LIST_CACHE_TTL` | wallet | `PT24H` | |
| `OUTBOUND_FEE` | wallet | `0.00` | Flat NGN, both types. |
| `LEDGER_CURRENCIES` | account | `NGN` | Section 4 said `USD`. |
| `TRANSFER_PIN_REQUIRED` | transaction | `true` | D2, unchanged. |

Still in section 4: `INTERNAL_JWT_SECRET`, `PIN_PEPPER`, `WITHDRAWAL_*`, `DEPOSIT_EXPIRY`, and the `STRIPE_*` keys. The `PAYOUT_SIM_*` placeholder in section 4 is replaced by the two `PAYOUT_SIM_*` variables above.

### 8.11 Changes per service (delta to section 4)

- **account-service:** new system accounts `PAYOUT_PAYABLE` and `PROVIDER_CLEARING:PAYSTACK:NGN`; Stripe's clearing account is renamed (D22); `LEDGER_CURRENCIES=NGN`. No new endpoints.
- **transaction-service:** `POST /api/v1/transactions` routes by `bankCode` (8.5) and gains the new fields. It calls wallet for ref validation and forwarding. The internal saga is unchanged. No new tables.
- **wallet-service:** banks; name enquiry (`name_enquiry`); the outbound pipeline with `type`; `POST /api/v1/wallet/withdrawals`; `GET /api/v1/wallet/payouts/{id}`; virtual accounts and the inbound consumer (6c); `unmatched_inbound`.
- **payment-service:** adapters (8.7), the Paystack webhook, outbox events, the inbound simulator (6c). Stripe unchanged.
- **fraud-detection-service:** the check request gains `type`. Rules unchanged in v1.
- **auth-service:** an optional `phone` on the profile (D24).
- **notification-service:** consumers for payout completed, failed and reversed, and for inbound credited (phase 7).
- **API-GATEWAY:** `/api/v1/banks` and `/api/v1/transfers/**` → wallet. `/api/v1/transactions` stays on transaction-service. `/internal/**` is never routed.

### 8.12 Sequencing and what I need from you

- Phase 6 builds the generic pipeline (8.3). 6b adds the external type on top of it. 6c adds inbound transfers.
- G1 gates phase 5. G2 gates 6c.
- **From you:** a Paystack test secret key in `.env` before 6b (gitignored, never in code or docs). The Stripe test key is already in `.env`, and G1 has run. Your answers to **D26 and D27** are needed before phase 5.

### 8.13 Decisions needed

Status after the review (2026-11-04): D20–D25 approved as recommended. D21 extends fix 5. D18 is changed by review fix 1. D19 is approved as a label only (review fix 4). D26 and D27 are new and open.


| # | Question | Recommendation |
|---|---|---|
| **D18** | The spec says every transfer must send `nameEnquiryRef`, but no `bankCode` → legacy saga "keeps old clients and smoke tests working". These conflict. | **Changed by review fix 1.** The ref is required only for a transfer to another bank. For our bank (no `bankCode`, or `= OWN_BANK_CODE`) it's optional, and if sent it must match. The PIN is required on every path when `TRANSFER_PIN_REQUIRED=true`. |
| **D19** | "Withdraw to the user's own account" isn't enforced anywhere. auth stores one `fullName`. | **Approved as a label, not a control (review fix 4).** Withdrawals only: the name from the enquiry should contain every token of `fullName` (case and accent insensitive), and a mismatch → 422. This catches mistakes, not abuse: a user can send the same money to any name as an EXTERNAL_TRANSFER under the same limits. Build nothing more on it (no linked accounts, no cool-downs in v1). |
| **D20** | Reversal after success: the spec's reversal entry refunds the amount only, so a bank reversal would keep any fee. | Refund the fee too in the reversal entry (DR FEE_REVENUE, CR wallet). No effect while the fee is 0. |
| **D21** | Fix 5 lists four give-back transitions. REVERSED isn't one, so a reversed payout would keep counting against the daily limit. | Count REVERSED as a fifth give-back transition, in the same transaction, with a test. This extends fix 5, so it needs your OK. |
| **D22** | Clearing names. The spec says `PROVIDER_CLEARING:{provider}`, 3.2 names system accounts per currency, and deposits use `STRIPE_CLEARING`. | `PROVIDER_CLEARING:{provider}:{currency}`. Stripe becomes `PROVIDER_CLEARING:STRIPE:NGN`. This is a rename, and it's free while nothing is built. |
| **D23** | Field names. The existing body uses `senderAccountNumber`, `receiverAccountNumber`, `description` and `type`. The spec's body uses `accountNumber` and `narration`, and has no sender. | Keep the existing names on `POST /api/v1/transactions`, and add `bankCode`, `pin` and `nameEnquiryRef`. The withdrawal endpoint uses the same names. This replaces D11's "accountNumber in the body" for these endpoints. |
| **D24** | DVA customer creation needs phone, name and email. auth stores no phone. | Add an optional `phone` to the profile. Virtual accounts require it (6c). This is a small auth change. |
| **D25** | The spec says to credit only from a verified webhook. | Also call Paystack's transaction verify before crediting, and check status and amount. Recommended. |
| **D26** | Stripe surplus. If Stripe charges more than the requested amount (VAT or a merchant-of-record add-on), where does the surplus go? It isn't credited either way (review fix 3). | Keep it out of the wallet and out of the ledger in v1. Log it, alert on it, and reconcile against Stripe's payout report. A liability account comes later, once we know how it's remitted. This isn't reachable on the card path G1 tested, because `ng_card` isn't enabled. |
| **D27** | Stripe settles in USD. G1 showed a fixed 32-cent processing fee plus 1 cent of conversion on a 71-cent charge. The ledger is in NGN. How are Stripe's fees and the NGN↔USD difference booked, and where does the rate come from? | Book Stripe's fees as an EXPENSE (a new seed, `PAYMENT_FEES`). Record the USD settlement against `PROVIDER_CLEARING:STRIPE:NGN` at the rate on the balance transaction. Reconciling that to the bank statement stays out of scope, as before. Needs your OK. |

### 8.14 Risks added

- **Money sent to the wrong account is final.** The name enquiry is the only check before a send. Mitigations: the 10-minute ref, rate limits and the approval threshold. The own-name rule (D19) is a label, not a mitigation, so it isn't counted here.
- **The sandbox doesn't test failure or reversal** (8.7). The first real reversal will be the first real test. Mitigations: fixtures, the simulator, and an alert on every REVERSED.
- **Stripe NGN amount, USD settlement and fixed fees** (8.8, G1, D26, D27). The ledger is NGN, Stripe settles USD, and the fixed processing fee dominates small deposits.
- **Name harvesting and enumeration** (8.4).
- **DVA availability** (8.8, G2).
- **Webhook ordering** between Paystack's events and recovery (8.3).
- **Unmatched inbound money** sits at Paystack until someone checks `unmatched_inbound`.
- **Size.** 6b is the largest phase in the plan. It adds a second provider and a second routing path. Expect it to take about as long as phase 6.

### 8.15 What I checked and what I didn't

- **Checked in the repo:** the current transfer request DTO (`TransactionRequest`: `senderAccountNumber`, `receiverAccountNumber`, `amount`, `description`, `type`); auth's `User` (one `fullName`, no phone); `.env.example` exists; `docs/deploy.md` doesn't; no ledger or wallet code exists yet.
- **Checked on the web** (standard search, not against our accounts): Paystack's transfer events and verify-by-reference endpoint; the HMAC-SHA512 webhook signature; test-mode transfers always succeeding (reported, not confirmed); the DVA flow, including `charge.success` with `dedicated_nuban`; Stripe's NGN presentment.
- **Checked against our Stripe test key** (2026-11-04, G1 only; see 8.8). Nothing else has been checked against our keys. Every Paystack endpoint path and status above is still to be confirmed in 6b, and G2 is still open.

### 8.16 Review of 2026-11-04: approval, fixes, and progress

**Approval.** Section 8 is approved with the changes below. D20, D21, D22, D23, D24 and D25 are approved as recommended. D19 is approved as a label only (review fix 4). D18 is changed (review fix 1). Phases 1–4 are being built. Phase 5 waits for D26 and D27, and G1's numbers are in 8.8.

The review's fixes and clean-ups are numbered 1–6 here, separately from the fixes 1–5 in section 0.

**Review fixes**
1. **PIN bypass (changes D18).** Every transfer path requires the PIN when `TRANSFER_PIN_REQUIRED=true`. The ref is optional on the own-bank path and required for another bank (8.5, 8.13). Tests: no `bankCode` and no PIN → 400; no `bankCode` and a wrong PIN → 403, and the failure counts toward the lockout. Built in phase 3 (transaction-service), not yet.
2. **Payout webhooks are triggers, not the truth.** On any `payment.payout.*` event, wallet calls `getStatus(key)` and acts on the answer. payment-service returns 200 once the outbox row is written, and 500 only if that write fails. New transition: `PROCESSING → REVERSED` posts the release entry only, with no settle, and gives back the daily usage (8.3). Tests: reversed with no success before it; success arriving after recovery already settled; the same event twice. Built in phase 6b, not yet.
3. **Stripe NGN amount rule (G1).** The wallet is credited exactly the requested amount, never more and never less. VAT or any merchant-of-record add-on is not credited. G1 has run, and its numbers are in 8.8. Phase 5 waits for D26 (surplus) and D27 (fees and USD settlement).
4. **D19 is a label, not a control.** It stays as written, and nothing more is built on it (8.3, 8.13).

**Clean-ups**
5. **Table rename.** `withdrawal` is now `outbound_payout`, and `withdrawal_daily_usage` is now `outbound_daily_usage` (one counter for both payout types). Fixes 2 and 5 use the new names and keep their meaning. Fix 5 now also covers REVERSED, as D21 approved.
6. **The plan agrees with itself.** Edited in place:
   - section 4, Infra: `mysql:8.4` → `mysql:9.7.4`, and the `.env.example` list points to 8.10;
   - 3.4: admin credit and deduct use `ADJUSTMENT_EQUITY`;
   - `STRIPE_CLEARING` → `PROVIDER_CLEARING:STRIPE:NGN` in 3.2 (two places) and 3.4. Section 3.5 never mentioned `STRIPE_CLEARING`, which I checked by search;
   - D5 and D6 in section 6 show the approved text;
   - section 4, transaction-service: "no OTP in Kafka" → OTP events only on `notification.otp` (D6), and "Optional PIN" → PIN required (review fix 1);
   - section 0 MySQL bullet and 8.1 updated to match.

**MySQL.** The version chosen is `mysql:9.7.4`, the current LTS (section 0 has the details and the fallback). The dev data has 23 accounts, not the 17 in the earlier notes. The restored counts match the 26.10 server exactly: 23 accounts (98000.00), 19 users, 23 transactions, 65 auth tokens, 7 payments.

**Progress.**
- Phase 1: done and committed.
- Phase 2, slice 1 (the posting core), is built and green. It is not committed, because the phase isn't finished. It includes `V2__ledger_core.sql`, `LedgerPostingService` (post and reverse), the ledger exceptions, `docs/adr/0001-locking.md`, and 14 tests: idempotency, funds rules, reversal, append-only triggers, and 50-way concurrency. The account-service suite runs 18 tests, all green on MySQL 9.7.4.
- Phase 2, still to do: V3 backfill (with its test), V4 (audit, outbox, processed_event, idempotency_record), the internal API and resource server with service tokens, admin credit and deduct, statement, reconciliation job and endpoint, `ledger.md`, the `accounts.balance` mirror write (expand), and the ArchUnit rule restricting who writes the ledger tables.
- Phases 3, 4: not started. Phase 5: waits for D26 and D27. 6b and 6c: not started.
