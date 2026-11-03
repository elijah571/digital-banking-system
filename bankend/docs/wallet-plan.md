# Wallet plan: deposits, withdrawals, double-entry ledger

Status: **approved 2026-11-03** with the changes in section 0. All other decisions use the recommended option.
Date: 2026-11-03

Contents: 0. Approved changes · 1. What exists today · 2. Bugs found while reading · 3. Design · 4. Changes per service ·
5. Phases · 6. Conflicts between the spec and the code (decisions needed) · 7. Risks

---

## 0. Approved changes (these override anything below)

**Decisions**
- **D5:** one `ADJUSTMENT_EQUITY` system account (a 7th seed) is the counter-account for **both** admin credit and admin deduct. An admin deduct isn't revenue, so `FEE_REVENUE` is not used for it.
- **D6:** as recommended, plus OTP events go on their own topic, `notification.otp` (retention 1 hour, `retention.ms=3600000`), **never** on the transfer topics. `transaction.otp.generated` is retired: it gets no new events, and its consumer is kept for one release (expand/contract).
- **D12:** **still open.** The answer came back as the template placeholder "[USD / NGN]". It must be answered before phase 2, because it sets the currency of the opening-balance backfill.

**Fixes added to the design**
1. **PIN brute force through parallel requests.** Each failed attempt is counted with an atomic
   `UPDATE pin_credential SET failed_count = failed_count + 1, locked_until = … WHERE user_id = ? AND (locked_until IS NULL OR locked_until < now())`
   inside a transaction that first takes `SELECT … FOR UPDATE` on the row. The check, the verify and the count happen under that lock, so attempts for one user run one after another.
   Test: 50 parallel wrong PINs → `failed_count` = 5 → locked, and the correct PIN is still rejected while locked.
2. **Stuck idempotency records.**
   - `idempotency_record` gets `lease_until` (default now + 30 s). A request that finds IN_PROGRESS with an expired lease may take the row over with a conditional `UPDATE … WHERE lease_until < now()`.
   - `deposit.idempotency_key` and `withdrawal.idempotency_key` are UNIQUE per user. A retry after the lease finds the existing resource and returns it instead of creating a new one.
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
5. **Daily withdrawal usage is given back.** When a withdrawal is rejected, cancelled, expired or failed, the amount is subtracted from `withdrawal_daily_usage` (on the withdrawal's own day) **in the same transaction** as the state change. Documented in `ledger.md`, tested for each of the four transitions.

**Changes found during implementation**
- **MySQL is pinned to `mysql:26.10`, not `8.4`.** Your dev volume was already created by 26.10 (`mysql:latest`), and MySQL can't read a data directory from a newer version, so 8.4 would make the existing data unreadable. Testcontainers uses the same tag. Flyway 12.4 logs a warning that it was only verified up to MySQL 9.4; the migrations themselves run fine.

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
ledger_account(id BIGINT PK, code VARCHAR UNIQUE NULL,         -- code for SYSTEM accounts, e.g. STRIPE_CLEARING:USD
               owner_type USER|SYSTEM, owner_ref VARCHAR NULL, -- accounts.id for USER
               type ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE,
               currency CHAR(3), balance DECIMAL(19,4), version BIGINT, created_at)
journal_entry (id BIGINT PK, type VARCHAR, reference VARCHAR, idempotency_key VARCHAR(100) UNIQUE,
               correlation_id VARCHAR, created_by VARCHAR, reverses_entry_id BIGINT NULL UNIQUE, created_at)
journal_line  (id BIGINT PK, entry_id FK, ledger_account_id FK, direction DEBIT|CREDIT,
               amount DECIMAL(19,4) CHECK (amount > 0), currency CHAR(3))
accounts      + ledger_account_id BIGINT NULL UNIQUE, + currency CHAR(3) NOT NULL DEFAULT 'USD'   (expand)
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
- System accounts are created per currency (`STRIPE_CLEARING:USD` …) by a Flyway seed for every configured currency.
- Reversal: an entry with the opposite lines and `reverses_entry_id` pointing at the original. The UNIQUE constraint means an entry can only be reversed once.
- **Hot-row note.** `STRIPE_CLEARING`, `WITHDRAWAL_PAYABLE` and `TRANSFER_IN_FLIGHT` take part in many entries, so they'd serialise everything through one row lock.
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
| Admin credit (reason required) | DR OPENING_BALANCE_EQUITY (adjustment), CR wallet (`adm-{uuid}` from Idempotency-Key) |
| Admin deduct (reason required) | DR wallet, CR FEE_REVENUE / adjustment (`adm-…`). See D5 |
| Deposit succeeded (webhook) | DR STRIPE_CLEARING, CR wallet (`dep-{id}-credit`) |
| Withdrawal hold | DR wallet, CR WITHDRAWAL_PAYABLE (`wd-{id}-hold`) |
| Withdrawal settled | DR WITHDRAWAL_PAYABLE, CR PAYOUT_CLEARING (`wd-{id}-settle`) |
| Withdrawal rejected, cancelled, expired or failed | DR WITHDRAWAL_PAYABLE, CR wallet (`wd-{id}-release`) |

Note on PAYOUT_CLEARING being an asset that gets a CR: settling makes it go negative. That's the money owed to us by, or already paid out through, the payout rail. It's zeroed when the provider statement is reconciled, which is out of scope. I'm keeping this as specified, and I'll document it in `ledger.md`.

### 3.5 wallet-service (new, port 8087, `wallet_db`)
Tables: `deposit`, `withdrawal` (each with `@Version`), `withdrawal_daily_usage(account_id, day, total, version)`,
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
   3. `SELECT … FOR UPDATE` the `withdrawal_daily_usage` row (insert it if missing) and check `total + amount ≤ limit`, then increment;
   4. insert the withdrawal REQUESTED and commit.
2. **Ledger HOLD:** `wd-{id}-hold`. account-service locks the wallet row, checks the funds and posts. If funds are short, the withdrawal goes to FAILED, the daily usage is decremented, and the client gets a 422.
3. **Fraud check:** a synchronous `POST /internal/v1/fraud/check` (new). It **fails closed**: if the fraud service is unavailable, the withdrawal goes to PENDING_APPROVAL.
4. **Gate:** if the amount is below `WITHDRAWAL_APPROVAL_THRESHOLD` and fraud is clean → APPROVED → payout. Otherwise → PENDING_APPROVAL, plus the outbox event `approval-required`.
5. **Payout:** APPROVED → PROCESSING → `PayoutProvider.send()`. Success → settle → COMPLETED. Failure → release → FAILED.
6. A recovery job (every minute) picks up withdrawals stuck in REQUESTED, APPROVED or PROCESSING longer than N minutes and replays the next step. Every ledger call uses the deterministic key, so a replay is a no-op if the step already happened. The payout provider also gets `withdrawalId` as its idempotency key.
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
`{eventId, type, version: 1, occurredAt, correlationId, payload}`, with headers `X-Request-Id` and `eventId`. The topics are the ones the spec lists, plus `payment.intent.succeeded|failed` (payment → wallet). Existing transfer topics keep their payload fields and gain `eventId`/`correlationId`, which is additive.

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
| **transaction-service** | Flyway. The saga moves to ledger hold/settle/release. The `Transaction` row and an outbox `transaction.initiated` are written **before** the hold, and a recovery job drives stuck rows. `@Version` on `Transaction` (fixes the verify race). Hashed OTP, `SecureRandom`, no OTP in Kafka (D6). `Idempotency-Key` on `POST /transactions`. SERVICE token for account calls. Optional PIN (D2). |
| **fraud-detection-service** | New internal `POST /internal/v1/fraud/check` (SERVICE token), with the same rules plus a withdrawal profile. SERVICE token for the balance read. Kafka path unchanged. |
| **payment-service** | Flyway. Internal `POST /internal/v1/payments/intents` (depositId and currency in metadata). The webhook writes outbox events with the Stripe event id, deduplicates through `processed_event`, and no longer swallows errors (it returns 500 so Stripe retries). The old `/create-order` keeps working (D9). |
| **wallet-service (new)** | Everything in 3.5 to 3.7: deposits, withdrawals, PIN, payout simulator, jobs, audit, outbox, idempotency. |
| **auth-service** | Flyway. `POST /internal/v1/auth/verify-password` (SERVICE token, rate-limited, audited). `audit_log` for role and enable changes. Publishes `auth.pin-otp.requested`, or wallet does it (wallet owns the OTP). |
| **notification-service** | Consumers for the wallet/ledger topics, deduplicated through `processed_event` (needs a small `notification_db` and Flyway). |
| **API-GATEWAY** | `/api/v1/wallet/**` → wallet. `/api/v1/admin/ledger/**` → account. `/api/v1/admin/audit` → D7. `X-Request-Id`. Wallet docs in the combined Swagger UI. Never routes `/internal/**`. Per-user rate limits for wallet POSTs. |
| **Infra** | Multi-stage Dockerfiles with the build context at `bankend/` if D1 = shared lib. Healthchecks on all containers (Java-free check through bash `/dev/tcp`, or add curl to the image). Pin `mysql:8.4` (LTS; Testcontainers uses the same tag), Redis and Kafka versions. Per-service MySQL users (D10). Add wallet to `compose.prod.yaml`. `.env.example`: `INTERNAL_JWT_SECRET`, `PIN_PEPPER`, `WITHDRAWAL_*`, `DEPOSIT_EXPIRY`, `PAYOUT_SIM_*`, `LEDGER_CURRENCIES`. |
| **CI/CD** | `.github/workflows/` at the **git root** (one level above `bankend/`): `ci.yml` (build, unit, Testcontainers, ArchUnit, compose up + smoke), `release.yml` (GHCR images tagged with the SHA → staging → staging rollback drill → prod behind environment approval), `rollback.yml`. `docs/deploy.md` lists every secret and server step. |

## 5. Phases (each ends green: build + all tests + commit)

1. **Foundations.** Testcontainers base in every service (existing `contextLoads` moves to it). Flyway V1 baselines. `validate` + `open-in-view=false`. Multi-stage Dockerfiles, healthchecks, pinned images. Shared lib skeleton (D1). ArchUnit money rule. CI `ci.yml`.
2. **Ledger core (account-service).** Money, ledger tables and triggers, posting with locks, reversals, reconciliation, statement, backfill and its test, audit, outbox, idempotency, resource server with SERVICE tokens, admin credit/deduct with reason. ADR `0001-locking.md` and `ledger.md`. Concurrency tests (50 parallel, opposite transfers).
3. **Transfers onto the ledger.** transaction-service changes from section 4. Update the smoke test and Postman. All 107 existing checks still pass, adapted only where the contract was deliberately changed (Idempotency-Key, reason), and each change is listed in the commit.
4. **wallet-service and PIN.** `threat-model-pin.md` **first**, then PIN, the auth verify-password endpoint, OTP, lockout, log-masking test.
5. **Deposits.** payment-service internal intent, webhook outbox, wallet consumer, expiry, tests (replay, bad signature, mismatch). The smoke test signs webhook payloads itself with `STRIPE_WEBHOOK_SECRET` via openssl, so no Stripe CLI is needed.
6. **Withdrawals.** State machine, limits, fraud endpoint, gate, maker-checker, payout simulator, expiry, recovery, all transition and concurrency tests.
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
| **D5** | Admin credit/deduct "against a system account": the spec doesn't name the counter-account. | Credit: DR `OPENING_BALANCE_EQUITY` (manual adjustment). Deduct: CR `FEE_REVENUE`. Or add an `ADJUSTMENT_EQUITY` system account; I'd prefer this one, as a 7th seed. `reason` is required as a query param (it stays `PUT … ?amount=&reason=`), and the response amounts are strings. |
| **D6** | Transfer OTP today: plaintext, included in the Kafka payload, logged on purpose in dev, one wrong attempt blocks the account. The spec (§11) wants hashed OTPs, single use, max attempts. | Hash it in Redis and allow 3 attempts. The 3rd wrong one keeps today's behaviour (FLAGGED + refund + block). The plaintext OTP still has to reach notification-service, so the Kafka event keeps it, but the topic goes on an allow-list for log masking, and `MAIL_LOG_CONTENT=false` in prod (already the case). |
| **D7** | "Audit in the same transaction" means every service keeps its own `audit_log`, but `/admin/audit` is a single endpoint. | Each service serves `GET /internal/v1/audit`. wallet-service's `/api/v1/admin/audit` fans out, merges by timestamp and pages with a cursor. The alternative is a Kafka-fed read copy, which is more moving parts. |
| **D8** | §10 only requires wallet and account to validate JWTs. transaction and payment would still trust headers, and :8082/:8085 would stay open in dev. | Make **transaction and payment resource servers too**, with the same small config from the shared lib, since they move money indirectly. This keeps the "direct port without JWT → 401" test honest for all money services. |
| **D9** | `POST /api/v1/payments/create-order` stays, but it credits nothing today, and deposits replace it. | Keep it unchanged and mark it deprecated in OpenAPI and the README. Deposits are the funding path. Removing it is a later contract step. |
| **D10** | "No other service may change a balance": every service connects as MySQL root, and root can also `DROP TRIGGER`. | Per-service MySQL users from an init script. Each gets only its own schema, and the app users have no TRIGGER/ALTER rights. Flyway runs as a separate `*_migrator` user, and only account_db's migrator can create triggers. Dev keeps `root` available for debugging. |
| **D11** | Accounts are addressed by `accountNumber` everywhere, but the spec writes `{id}` and `accountId`. | Use `accountNumber` in paths and bodies (`/accounts/{accountNumber}/statement`, `"accountNumber"` in the withdrawal body), as today. |
| **D12** | There's no currency anywhere, and Stripe is hard-coded to USD. | Add `currency` to accounts, defaulting to `USD` and backfilled as USD. One currency per wallet. A transfer across currencies → 422. `LEDGER_CURRENCIES=USD` seeds the system accounts. If your accounts are actually NGN, tell me; the backfill currency is then a one-line change before phase 2. |
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
