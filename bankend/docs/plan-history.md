# Wallet plan: dated history (8.16 to 8.24)

Moved out of wallet-plan.md on 2026-11-04, unchanged. These entries are the dated logs of the review and the build: approvals, answers and evidence. The design, with the decisions folded into it, lives in wallet-plan.md. A reference to 8.16 to 8.24 in wallet-plan.md points to this file.

### 8.16 Review of 2026-11-04: approval, fixes, and progress

**Approval.** Section 8 is approved with the changes below. D20, D21, D22, D23, D24 and D25 are approved as recommended. D19 is approved as a label only (review fix 4). D18 is changed (review fix 1). Phases 1 and 2 are built. Phase 5 comes after phase 4. D26 and D27 are approved (8.17), so they no longer block it. G1's numbers are in 8.8.

The review's fixes and clean-ups are numbered 1–6 here, separately from the fixes 1–5 in section 0.

**Review fixes**
1. **PIN bypass (changes D18).** Every transfer path requires the PIN when `TRANSFER_PIN_REQUIRED=true`. The ref is optional on the own-bank path and required for another bank (8.5, 8.13). Tests: no `bankCode` and no PIN → 400; no `bankCode` and a wrong PIN → 403, and the failure counts toward the lockout. Sequencing (8.22): phase 3 adds the `pin` field and the wallet call behind `TRANSFER_PIN_REQUIRED`, which defaults to `false`. Phase 4 builds the PIN, flips the flag to `true`, and adds the tests above. Nothing is built yet.
2. **Payout webhooks are triggers, not the truth.** On any `payment.payout.*` event, wallet calls `getStatus(key)` and acts on the answer. payment-service returns 200 once the outbox row is written, and 500 only if that write fails. New transition: `PROCESSING → REVERSED` posts the release entry only, with no settle, and gives back the daily usage (8.3). Tests: reversed with no success before it; success arriving after recovery already settled; the same event twice. Built in phase 6b, not yet.
3. **Stripe NGN amount rule (G1).** The wallet is credited exactly the requested amount, never more and never less. VAT or any merchant-of-record add-on is not credited. G1 has run, and its numbers are in 8.8. D26 (surplus) and D27 (fees and USD settlement) are approved (8.17). Phase 5 comes after phase 4.
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
- Phase 2, slice 1 (the posting core), is built and green. It was committed as `01015df` at your request. The phase isn't finished. It includes `V2__ledger_core.sql`, `LedgerPostingService` (post and reverse), the ledger exceptions, `docs/adr/0001-locking.md`, and 14 tests: idempotency, funds rules, reversal, append-only triggers, and 50-way concurrency. The account-service suite runs 18 tests, all green on MySQL 9.7.4.
- Phase 2, still to do: V3 backfill (with its test), V4 (audit, outbox, processed_event, idempotency_record), the internal API and resource server with service tokens, admin credit and deduct, statement, reconciliation job and endpoint, `ledger.md`, the `accounts.balance` mirror write (expand), and the ArchUnit rule restricting who writes the ledger tables.
- Phase 3: first slice (gateway routes, 8.22) in progress; transaction-service waits for approval. Phase 4: not started. Phase 5: starts after phase 4 (D26 and D27 are approved, 8.17). 6b and 6c: not started.

### 8.17 Second review (2026-11-04): commits, D26, D27, CARD_DEPOSIT_MIN

**Approved.**
- The idempotency check runs under the account locks, with the UNIQUE key as the backstop (ADR 0001).
- `outbound_daily_usage` (8.3) and the new MySQL volume name are approved. The old 26.10 volume, `digital-banking-system_mysql_data`, is deleted by the user after phase 2 is committed.
- Commits: `51bf69a` (MySQL 9.7.4 pin), `9d2cc15` (plan), `01015df` (ledger slice 1). From now on, one commit per green slice.
- **D26 (changed):** money received is never left off the ledger. Details in 8.13 and `docs/ledger.md`.
- **D27 (approved with limits):** the fee is its own entry, posted outside the credit path. Details in 8.13 and `docs/ledger.md`.

**D26 and D27 in the ledger (slice 2a, built).**
- `DepositSettlement`: the credit is the requested amount, and only if the full amount arrived. Everything else received goes to suspense. A shortfall is `FAILED`.
- `CardDepositPostings.settle`: posts `dep-{id}-credit` and `dep-{id}-suspense` in one transaction. A replay changes nothing. A replay that changes the requested amount is a conflict, and writes nothing.
- `CardDepositPostings.recordFee`: posts `dep-{id}-fee`, DR `PAYMENT_FEES`, CR `PROVIDER_CLEARING:STRIPE:NGN`.
- `StripeFees.toNgn`: the fee divided by the balance transaction's rate, HALF_EVEN to 2 decimals. The G1 sample (0.33 USD at 0.000710374) is ₦464.54. The HALF_EVEN ties are tested at 0.015, 0.025 and 0.035.
- V3 adds `DEPOSIT_SUSPENSE:NGN` (LIABILITY) and `PAYMENT_FEES:NGN` (EXPENSE).
- The suspense alert is not wired yet. It should be an outbox row in the same transaction as the suspense entry. V5 now has the table (slice 2c), and wiring the row is part of the deposit flow in phase 5.

**Fee measurement and the proposed `CARD_DEPOSIT_MIN`.** Seven test-mode charges on 2026-11-04, at the same rate (0.000710374 USD per NGN):

| Amount (NGN) | Gross (USD ¢) | Stripe fee (USD ¢) | Fee (NGN) | Fee as % of amount |
|---:|---:|---:|---:|---:|
| 1,000 | 71 | 33 | 464.54 | 46.45% |
| 2,000 | 142 | 35 | 492.70 | 24.64% |
| 5,000 | 355 | 44 | 619.39 | 12.39% |
| 10,000 | 710 | 58 | 816.47 | 8.16% |
| **20,000** | 1,421 | 85 | 1,196.55 | **5.98%** |
| 50,000 | 3,552 | 169 | 2,379.03 | 4.76% |
| 100,000 | 7,104 | 307 | 4,321.67 | 4.32% |

- The fee fits 30 US cents plus 3.9% of the USD amount, which is about ₦422 fixed plus 3.9%. The fixed part is what makes small deposits expensive.
- Stripe refuses amounts below 50 US cents. That's about ₦704 at this rate. A ₦500 charge was refused.
- **Proposal:** `CARD_DEPOSIT_MIN = 20000`. The fee is then 5.98%, about ₦1,197. ₦10,000 would be 8.16%. The fee is ours, because the wallet is credited exactly the requested amount. Caveats: these are test-mode charges, seven samples, and live pricing may differ. Check on a live account before go-live.

**Decided 2026-11-04 (8.19):** a receipt in another currency is rejected and alerted, and the full receipt is kept in a review table. There is no per-currency suspense.

**Progress.**
- Phase 2, slice 2a (deposit postings, V3, the fee conversion, `docs/ledger.md`): committed as `b773bbc` (code) and `8f750a1` (docs). The suite was 35/35 green.
- Phase 2, slice 2b (V4 opening-balance backfill, with its test): built. The backfill test runs the upgrade on a scratch database with the V1 schema and existing rows. It checks that every wallet mirrors its account and that the journal balances, and that a negative account stops the migration before any write. The suite is 37/37 green. The guard was first written in a form MySQL 9.7 rejects, and the test caught it before any data was touched.
- Phase 2, slice 2c: V5 (`audit_log` with append-only triggers, `outbox`, `processed_event`, `idempotency_record` with a lease). `AuditLogWriter` and `OutboxWriter` run only inside the business transaction (`MANDATORY`). `IdempotencyService` implements begin, complete and takeover. Tests: audit rows cannot be changed or deleted; an outbox row and its ledger entry commit or roll back together; the envelope is correct; the crash-recovery case from fix 2 moves money once. The suite is 46/46 green.
- Phase 2, slice 2d: `accounts.balance` mirrors its wallet in the same transaction (expand, 3.2), and a test checks it. `LedgerWriteArchitectureTest` (ArchUnit) fails if any class other than the four table owners writes through `JdbcTemplate`. The rule was checked by planting a violation: it fails and names the method, and it passes once the probe is removed. The suite is 48/48 green.
- Phase 2, slice 2e: the outbox relay. `OutboxRelay` publishes unsent rows with `FOR UPDATE SKIP LOCKED` and marks a row sent only after Kafka acknowledged it. It runs on a schedule and can also be called directly. It uses its own string-valued producer (`OutboxKafkaConfig`), because the default producer would quote the envelope. Tests: each event reaches Kafka once, and a second pass does not resend it. The first version rolled back the whole batch on one failure, so a single bad row held back everything behind it. The fix records the failure on that row and carries on. Along the way, the relay's producer was reading the bootstrap address from a property, which Testcontainers' service connection does not change, so my first test runs published to the dev broker on localhost:9092. The producer now takes the address from Boot's `KafkaConnectionDetails`. The suite is 50/50 green, and every producer targets the test container.
- **Cleanup for you:** my first two relay test runs left three topics on the dev broker (`kafka-broker`, localhost:9092): `wallet.test` and two `relay.test.*`. They hold only test messages. I did not delete them. Remove them with `docker exec kafka-broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic <name>` if you don't need them.
- Phase 2, slice 2f: the reconciliation checks (`ReconciliationService`: each cached balance against its lines, the journal balance per currency, and the `accounts.balance` mirror) and the hourly `ReconciliationJob`. When a check fails, the job writes an audit row and an outbox alert (`ledger.reconciliation.failed`) in one transaction, and the relay publishes the alert. Each check is broken on purpose in a test and then undone, and the job's alert is checked. The suite is 54/54 green.
- Phase 2, still to do: the reconciliation endpoint (`GET /api/v1/admin/ledger/reconciliation`, after 8.18), the statement, admin credit and deduct (with `Idempotency-Key`, through `IdempotencyService`), the internal API and resource server with service tokens, and the consumer side (`processed_event` dedupe) when the first consumer is built. The resource server needs a decision on sequencing, because transaction-service calls account-service today with headers and no token (8.18). The old transfer path still writes `accounts.balance` on its own, so the mirror holds only for ledger postings until phase 3 moves transfers onto the ledger.

### 8.18 Decision needed: when account-service requires service tokens

**The problem.** 3.7 and fix 4 say account-service becomes a resource server, and `/internal/**` needs a SERVICE token. Today transaction-service, fraud and payment call account-service's deduct and credit with headers and no token. If account-service requires tokens before those callers send them, the running transfer flow breaks, and the phase 2 smoke test fails, so phase 2 can't end green. The new internal ledger endpoints have no existing caller. They can require SERVICE tokens from the start, so they don't depend on this decision.

**Options.**
- **A. Switch in phase 3.** Phase 2 ends with the header trust still in place. Callers move to tokens and the resource server switches on in phase 3. The gap stays open for the length of phase 2.
- **B. Dual mode in phase 2 (recommended).** account-service accepts a SERVICE token, and it also accepts the legacy headers only while `ACCOUNT_LEGACY_HEADERS_ENABLED=true`. That flag is `true` in dev and in the compose stack until phase 3, and `false` in prod from day one. Phase 3 moves the callers to tokens and sets the flag to `false`. A test checks that with the flag off, a direct call with no token gets 401.
- **C. Move the caller change into phase 2.** transaction-service, fraud and payment switch to SERVICE tokens first, then account-service requires them. This mixes two phases in one commit series.

**What I'd do.** B. It keeps every phase green, and it closes the gap at the end of phase 3, not at the end of phase 6. A still leaves the gap open, and C changes the phase boundaries you approved.

**Decided 2026-11-04: option B, with the limits in 8.19.**

### 8.19 Answers of 2026-11-04 (third round), and the test incident

**Approved.**
1. **8.18, option B**, with these limits:
   - `ACCOUNT_LEGACY_HEADERS_ENABLED` defaults to `false`. Startup fails if it is `true` under the `prod` profile.
   - The legacy path covers only the endpoints today's callers use. The answer listed three, but there is a fourth: `GET /api/v1/accounts/{accountNumber}` is called without a token by transaction-service and payment-service, and excluding it would break both. **It is included.** The legacy set is `PUT …/deduct`, `PUT …/credit`, `GET …/balance` and `GET /{accountNumber}`, for 10-digit account numbers only. Please confirm.
   - Each request that gets in through the legacy path logs a warning with the caller: remote address, user agent, and `X-User-Id` if present. Today's callers send no identity, so until phase 3 adds service tokens with `sub`, a caller can only be identified by address.
   - The internal ledger API and every new endpoint never accept the legacy path. The internal ledger API accepts service tokens only. My reading of "every new endpoint accepts service tokens only" is that new user-facing endpoints (the statement, the admin reconciliation and adjustments) take user tokens, since the gateway forwards user tokens, and that they never take the legacy path. Please confirm.
   - Phase 3 checklist: added to section 5, phase 3.
2. **CARD_DEPOSIT_MIN = 20000** is the default (8.10). Small amounts can still come in by bank transfer, which has no minimum (6c).
3. **Currency mismatch:** reject and alert, with no per-currency suspense. The full receipt is kept in a review table, `receipt_review`, in account_db next to the ledger, shaped like `unmatched_inbound`. The review row and the outbox alert are written in the same transaction.
4. **Test isolation:** the cause and the fix are below. The three leaked topics are deleted.
5. **Rebuild of account-service:** after a fresh dump, V2 to V5 apply to the dev database, and the reconciliation result is recorded in the report.
6. **The old 26.10 volume** stays until phase 2 is finished.

**Cause of the dev-broker writes.** The outbox producer read `spring.kafka.bootstrap-servers` as a plain property. That property defaults to `localhost:9092`, the dev broker's published port. Testcontainers' `@ServiceConnection` changes Boot's connection details, not the property, so the producer went to the dev broker. The dev database was not touched: its counts were 23 accounts (98000.00), 19 users, 23 transactions, 65 tokens and 7 payments before and after the test runs. Only Kafka received writes, and the three test topics held four test messages.

**Fix.** The property is set to the container's address in tests. The producer takes its address from `KafkaConnectionDetails`. A test context refuses to start if Kafka, MySQL, the datasource or `spring.data.redis.port` is on 9092, 3306 or 6379. I checked this by pointing the property at 9092: the context refused to start and named the property. Account-service has no Redis code, so its `spring.data.redis` block is removed.

**A second bug, found by the same test run.** The scheduled relay called `publishPending()` on itself, which bypasses the `@Transactional` proxy. The row locks were released after each SELECT, so concurrent passes sent the same rows: 85 deliveries for 20 events in the new concurrency test. The relay's scheduled pass now opens its own transaction. The concurrency test reproduces the failure on the old code and passes on the new code.

The account-service suite is 60/60 green on MySQL 9.7.4.

### 8.20 Phase 2: complete (2026-11-04), the dev rebuild, and what carries forward

**Built and committed** (`01015df` to `cd41b79`): the posting core, the deposit settlement and fee entries (D26, D27), the backfill (V4), audit, outbox and idempotency (V5), the balance mirror and writer rule, the relay, reconciliation (service, hourly job, endpoint), the internal ledger API, the statement, admin credit and deduct, and the resource server with service tokens and the dev-only legacy path (8.18, 8.19). The account-service suite is 94/94 green on MySQL 9.7.4.

**Dev rebuild (your item 5).** I took a fresh dump to `~/backups/mysql/` before migrating (`digital-banking-mysql-9.7.4-2026-11-04-pre-v2-v5.sql`, counts in `pre-v2-v5-counts.txt`). V2 to V5 applied on the dev database, and the account-service container is healthy. Reconciliation on the 23 accounts, read-only, against the same checks as `ReconciliationService`:

| Check | Result |
|---|---|
| Accounts linked to a wallet | 23 of 23 |
| `accounts.balance` = wallet balance | 23 of 23 match (0 mismatches) |
| Total of `accounts.balance` | 98000.00 |
| Total of wallet balances | 98000.0000 |
| Opening-balance equity | -98000.0000 (one opening entry per account, 23 entries) |
| Journal lines: debits = credits | 23 debit lines and 23 credit lines, both 98000.0000 |
| Cached balance vs. signed lines | 0 mismatches |
| Currencies with unbalanced journals | 0 |

The dev stack's gateway is healthy. An unauthenticated call to the legacy balance returns 200 and is logged. The internal ledger path and the account list return 401 without a token.

**A caveat that matters now.** The old transfer saga still changes `accounts.balance` directly through deduct and credit, without a ledger entry. From now on, any transfer on the dev stack will move `accounts.balance` away from the wallet, and the hourly reconciliation job will alert. That is expected until phase 3 moves transfers onto the ledger. Until then, keep smoke-test traffic off the dev stack if the alerts are a problem.

**Decisions I took, to confirm:**
1. **Legacy set.** The fourth endpoint, `GET /api/v1/accounts/{accountNumber}`, is in the legacy set (8.19).
2. **New endpoints take user tokens.** "Service tokens only" applies to the internal ledger API. The statement, reconciliation and admin adjustments take user tokens, ADMIN or owner. The gateway forwards user tokens, so this is the only reading that works.
3. **Admin paths.** D5 wrote `PUT /api/v1/accounts/{n}/credit?amount=&reason=`. The new admin credit and deduct are at `PUT /api/v1/admin/accounts/{n}/credit` and `…/deduct`, and the old paths stay unchanged for the old callers until phase 3. Confirm, or tell me to reuse the old paths when phase 3 removes them.
4. **Statement paging.** Keyset on the line id, as section 3.2 says. D14 asked for `page`, `size` and `totalElements`. The statement returns `nextAfterLineId` and no total count, since counting lines is expensive.
5. **Replays keep their status.** A replayed admin adjustment returns the stored response with its original status (201). The ledger's own replay returns 200 (3.6). Both follow the spec.
6. **Stored replays are JSON-normalised.** MySQL rewrites the stored JSON, so a replayed body differs from the first answer in whitespace only. It's semantically identical. A `TEXT` column would keep the bytes, but that would need a V6 migration for a cosmetic difference, so I didn't add one.

**Carried forward.** Gateway routes for `/api/v1/admin/**`: done 2026-11-04, see 8.22. Dev deploy: done 2026-11-04, see 8.22. Still open: the phase 3 checklist (above), including D3 for the legacy endpoints and the `transaction.completed` consumer.

### 8.21 Follow-up: the receipt review (decision 3 of 8.19)

Built after 8.20. `V6__receipt_review.sql` adds `receipt_review`, in account_db next to the ledger, shaped like `unmatched_inbound`. `ReceiptReview.quarantine` keeps the full receipt, with status `OPEN` and a unique key on (source, provider reference, reason). It also writes the alert `ledger.receipt.quarantined` to the outbox, in the same transaction. Nothing is posted to the ledger. A repeat of the same receipt is recorded once and alerted once. The suite is 96/96 green.

Not yet wired: the deposit flow (phase 5) calls `quarantine` when a receipt's currency does not match its intent. The admin resolution (refund or reclassify) is not built yet.

### 8.22 Phase 3: order, notes, and the first slice (2026-11-04)

**Stale lines fixed** (no design change): the status lines at the top and in section 8; the account count (23, not 17) in sections 1 and 7; the phase-5 wording in 8.8, 8.12, 8.16 and section 5 (phase 5 comes after phase 4, D26 and D27 are approved); the name `ACCOUNT_LEGACY_HEADERS_ENABLED` in 8.18; the idempotency check in 3.2 (under the locks, ADR 0001); and the posting steps 7 and 8 in 3.2, which now say the caller writes the audit and outbox rows. The generic `ledger.entry.posted` event is recorded as not built. `docs/ledger.md` has the same status and endpoint corrections.

**Correction to an earlier note.** I wrote "phase 5 starts after phase 2". That was wrong: section 5 orders phases 3 and 4 before phase 5, and phase 5 now starts after phase 4.

**Order problem (review fix 1 and phase 4).** Review fix 1 needs the PIN on every transfer, but PIN verification lives in wallet-service, which is phase 4. Decided:
- Phase 3 adds the `pin` field and the call to wallet's PIN verify behind `TRANSFER_PIN_REQUIRED`, which defaults to `false`. No temporary PIN store goes into transaction-service.
- Phase 4 builds the PIN, flips the default to `true`, and adds the review-fix-1 tests: no `bankCode` and no PIN gives 400; no `bankCode` and a wrong PIN gives 403 and counts toward the lockout.
- Startup must fail under the `prod` profile if `TRANSFER_PIN_REQUIRED` is `false`.

**What that means for deploys.** Once transaction-service's phase-3 code lands, a `prod` start of that service fails until the PIN flag is `true`, which is phase 4. Phases 3 and 4 therefore ship to prod together. Until then, phase 3 runs on dev only.

**Memory (review item).** Dev containers get `mem_limit` values that match prod. Each Dockerfile's `-XX:MaxRAMPercentage=75` then sizes the JVM heap to 75% of that limit. Tests keep sharing one MySQL and one Kafka per JVM.

**First slice: gateway routes.** `/api/v1/admin/**` had no gateway route, so admin endpoints were unreachable through the gateway. This slice adds the route to account-service and requires ADMIN for the path in the gateway's security chain. The statement route already existed under `/api/v1/accounts/**`, and this slice adds tests for it. Tests: USER gets 403 on admin paths, ADMIN gets 200, a forged `X-User-Role` header does not elevate, and unauthenticated calls get 401.

**Carry-forward.** When D7's `/api/v1/admin/audit` is built, it needs its own gateway route placed before `/api/v1/admin/**`, because that wildcard currently sends it to account-service.

**Not started.** Any transaction-service edit waits for approval. The uncommitted `TransactionalService.java` diff is still in the working tree and is untouched.

**Evidence (2026-11-04).**
- **Gateway tests:** 7 of 7 green. Removing the admin security rule makes the two 403 tests fail, and the rule was then restored.
- **Dev rebuild:** account-service and api-gateway rebuilt from the committed sources. Flyway history on dev is now V1 to V6, and V6 (`receipt_review`) is applied. All seven JVM containers and the three infrastructure containers are healthy, with zero restarts and no OOM kills.
- **Live checks through the gateway:** a login as the dev admin, then:
  - reconciliation without a token: 401; with the admin token: 200, `clean: true`, no issues;
  - statement with the admin token: 200, with the opening balance and the running balance;
  - statement without a token: 401; a bad token: 401.
- **Memory:** the dev stack is 2.24 GiB across 10 containers, measured after the restart. The seven JVMs are capped at 3.9 GiB combined. The host has about 0.9 GB available and 3.2 GB of its 4 GB swap in use. Before the caps, the first sample was 1.67 GiB; the difference is the JVM heaps growing after the restart.

### 8.23 Audit on internal ledger postings (2026-11-04)

Built before phase 3 uses the internal API, as ordered.

- **`ServicePostings`** (new). `post` and `reverse` write one audit row in the same transaction as the posting. Action `LEDGER_ENTRY_POSTED` or `LEDGER_ENTRY_REVERSED`; entity `JOURNAL_ENTRY` with the entry id; actor = the service token's `sub`; correlation = `X-Request-Id`. The details hold type, reference, createdBy and correlationId, or `reverses` for a reversal.
- **No double rows.** A replay writes nothing. Admin adjustments do not pass through `ServicePostings`: `AdminAdjustments` keeps its own `ADMIN_CREDIT` or `ADMIN_DEDUCT` row.
- **`X-Request-Id`.** The request's value is used when it has 1 to 100 visible ASCII characters, which matches `audit_log.correlation_id`. It is generated when absent, and echoed in the response. **Gateway generation (3.9) is not built.** It belongs to phase 7, with `X-Request-Id` end to end. Until then, a caller that wants a trace id must send one.
- **Token gate.** A service token needs a non-blank `sub` of at most 100 characters, because `audit_log.actor` is VARCHAR(100). Otherwise the token gets 401.
- **Not built:** `ledger.entry.posted` stays not built (3.2, step 7).

**Tests.**
- `LedgerApiTest`: 14 (4 new). One audit row with the token subject and the request id; a replay adds no row; a reversal is audited against the entry it reverses; a missing request id is generated and an oversized one gets 400 with nothing moved; a token with no subject gets 401.
- `ServicePostingsTest` (new, 3): both commit together; both roll back when the caller fails afterwards; and the posting rolls back when the audit write fails. The last case uses a real MySQL error: an actor of 101 characters fails the insert after the entry is written.
- `AdminLedgerApiTest`: one assertion added. An admin credit has no posting audit row of its own.
- Full account-service suite: **103 tests, 0 failures, BUILD SUCCESS** on MySQL 9.7.4 and Kafka via Testcontainers. The dev stack was stopped for the run.

**Not done.** No mutation check yet: I have not confirmed that the rollback test fails if the audit write runs in its own transaction. No dev deploy: the change is on the branch only. The dev stack is still stopped.

### 8.24 Phase 3, slice 3a: the callers send service tokens (2026-11-04)

Legacy headers stay on in this slice, so callers behave as before. The slice prepares their removal.

- **`libs/banking-common` `ServiceTokens`** (new). Mints the HS256 service token from 3.7: `iss=elibanking-internal`, `role=SERVICE`, `sub` = the calling service, `aud` = the service being called. It lasts 5 minutes and is reused until a minute before it expires. Both callers use this one class (D1).
- **transaction-service.** A Feign interceptor adds `Authorization: Bearer` to every account-service call, with `sub = transaction-service`.
- **payment-service.** `AccountClient` sends the same header with `sub = payment-service`.
- **Compose.** Both callers get `INTERNAL_JWT_SECRET`, the same value account-service uses, and compose stops without it. Each service also refuses to start without it.
- **Dependency scope.** `banking-common` moves from test scope to compile scope in transaction-service and payment-service, because main code uses it now. Payment-service has no `RestClient.Builder` bean, so `AccountClient` builds its own client; a package-private constructor takes a builder for the test.

**Tests.**
- `banking-common`: 14 green, including 3 new `ServiceTokensTest` cases. They check the claims and the signature, reuse and renewal, and the refusals (short secret, blank or long subject, blank audience).
- transaction-service: 5 green, including the new `AccountServiceAuthTest`.
- payment-service: 5 green, including the new `AccountClientTest`. A mock server checks that the header verifies with the shared secret and carries the right `sub` and `aud`.
- account-service: 25 green across `ServiceTokenContractTest` (new), `ApiSecurityTest` and `LedgerApiTest`. A token from the shared library gets 404 (accepted, unknown account). A token for another audience, or signed with another secret, gets 401.
- `docker compose -f compose.yaml -f compose.prod.yaml config -q` exits 0. The quiet form prints nothing, so no values are shown.

**Not done.** No dev rebuild or deploy, per the rule that nothing deploys between phases 3 and 4. No live check. The dev flag stays `true`.

**Next, slice 3b.** Set the dev flag to `false` and check that a call with no token gets 401. Then remove the legacy path and its tests. The old `deduct` and `credit` paths stay until transfers move onto the ledger (slice 3c), because transaction-service still uses them.

### 8.25 Smoke on a throwaway stack, the D8 check, and slice 3c.1 (2026-11-04)

- **Where the smoke test ran.** A throwaway compose project (`smoke`), built from a worktree at `de09a3f` with fresh volumes. The dev stack stayed stopped. After teardown with `down -v`, no `smoke_*` volumes were left, and the dev volumes `digital-banking-system_mysql_data` and `digital-banking-system_mysql_data_lts97` were still there. The smoke script reads verification links from the notification log: on the throwaway stack that log was read with `NOTIFICATION_LOG`, because the script's default `docker compose logs` reads the dev project.
- **Result: 104 passed, 3 failed, 1 skipped.**
  - 3 failed: admin credit, deduct and the insufficient-deduct check on accounts opened through the API. Each returned 404 "no wallet for account". See the finding below.
  - 1 skipped: `POST /payments/create-order` returned 502, so the script skips it. The cause was not investigated (Stripe reachability or the key).
- **Reconciliation on that stack: clean, 0 issues. This result says little.** Accounts opened through the API have no ledger wallet, so the checks never reach them. The smoke transfers moved their `accounts.balance` without a wallet to compare against, so the check cannot see them.
- **Finding: accounts opened after the V4 backfill have no ledger wallet.** Nothing in account-service's main code creates a `ledger_account` row when an account is opened. The only creation is the one-time V4 backfill. Effects: admin credit and deduct return 404 for new accounts, and transfers on the ledger (3c.3) cannot settle to or from them. Section 3.4 already describes the opening entry `acct-{id}-opening` (D4), so the wallet-at-opening step is a gap in phase 2. Proposed slice, before 3c.3: create the wallet in the same transaction as the account row, set `accounts.ledger_account_id`, and post the D4 opening entry behind `ACCOUNT_OPENING_DEPOSIT_ENABLED`. Awaiting approval.
- **D8 check: confirmed.** transaction-service and payment-service read identity from `X-User-*` headers, neither has a resource server, and ports 8082 and 8085 are published in dev. Slice 3c.2.
- **Slice 3c.1 committed** (`3a587b9`): V2 adds `transactions.version`, `@Version` is on `Transaction`, and `TransactionVersionTest` checks that the second of two writers loses. transaction-service 6/6 green. No mutation check.

### 8.26 Slice 3c.2 (D8) and the smoke run through the gateway (2026-11-04)

- **D8 built** (`14d1126`). transaction-service and payment-service validate the user tokens that auth-service issues. X-User-* headers are never identity. transaction-service 11/11 and payment-service 9/9 green. Tests: a forged X-User-Id with no token gets 401 and never reaches account-service; user A on user B's account gets 403; an admin on someone else's account gets 403; the owner's transfer gets 201; a token with no subject gets 401. For payment: the same checks, plus the public webhook still rejects a bad signature with 400.
- **Smoke through the gateway** on a throwaway stack built from `14d1126`. Transaction and payment were rebuilt. The other images were reused, because their code has not changed since the earlier run. Result: **105 passed, 3 failed, 0 skipped.** The 3 failures are admin credit and deduct on accounts with no wallet (the 8.25 finding). The earlier skip of `create-order` now passes.
- **Reconciliation on that stack: clean, 0 issues.** As in 8.25, this says little: the smoke accounts have no wallets, so the checks never reach them.
- **Two earlier runs failed at email verification** and then cascaded into 401s. The cause is in the smoke harness, not the services. `from_logs` takes the newest verification link when it looks. The resend email arrives asynchronously, so the lookup used the first link, which the resend had already invalidated. The run that passed happened to have the resend email arrive in time. Open: the harness should wait for the latest email before it takes a link.
- The dev stack stayed stopped. The throwaway stack was torn down with `down -v`, and the dev volumes were checked after teardown.
- **Open, awaiting approval:** the wallet-at-opening slice (8.25).

### 8.27 Harness fix, the D8 proof, wallet at opening, and two product races (2026-11-04)

- **Harness fix** (`b40bceb`). Every lookup in `smoke-test.sh` now takes a mark (the notification log's line count) before the action, then waits up to 30 s for a message logged after it. That covers the signup link, the resend link (now verified, as a new check, so the run has 109 checks), the transfer OTP and the reset token.
- **Correction to 8.26.** The resend explanation in 8.26 was wrong. The script's resend is for dave, and it consumes no link. The real cause is the product race below.
- **Throwaway runner** (`f702df3`). `scripts/smoke-throwaway.sh [commit]` runs the whole check in one command, then checks the dev volumes.
- **D8 proof** (against `3a587b9`, the commit before D8, with the new auth tests copied in). All four forged-header and no-subject tests fail on the old code. transaction-service: `aForgedUserIdHeaderWithNoTokenIsRefused` errors with a NullPointerException. The forged request got past the gate and reached the ownership check, and the unstubbed mock returned null. `aUserCannotTransferFromSomeoneElsesAccount` and `anAdminCannotTransferFromSomeoneElsesAccountEither` get 201 where 403 is expected. `aTokenWithNoSubjectIsRefused` gets 201 where 401 is expected. payment-service: the forged header errors the same way. `aUserCannotPayIntoSomeoneElsesAccount` gets 502, which means it reached Stripe, where 403 is expected. The no-subject test gets 502 where 401 is expected. The forged-header test would show a 201 if the account were stubbed; that's a cleaner proof, and I haven't rerun it.
- **Wallet at opening** (`14d871b`). Every new account gets its wallet in the same transaction as the account. The deposit goes through the ledger, behind `ACCOUNT_OPENING_DEPOSIT_ENABLED`: on in dev, off in prod. A non-zero deposit with the flag off gets 400. V7 repairs the accounts that have no wallet, and a second run changes nothing. Reconciliation reports wallet-less accounts. account-service 112/112 green.
- **Throwaway run on `14d871b`**: 108 passed, 1 failed, out of 109. The one failure is the Dave resend link, below. The admin credit and deduct checks pass now, which closes the 8.25 finding.
- **Reconciliation on that stack: not clean, 2 issues, both expected.** Alice's account reads 500 while her wallet holds 10000, and Bob's account reads 10000 while his wallet holds 500. The smoke transfers went through the old transfer path, which changes `accounts.balance` without the ledger, so the wallets still hold the opening deposits. This is the drift the plan predicted until 3c.3. The check now sees these accounts, where before it could not.
- **Product race 1: auth-service (the cause of the verification 400s).** `AuthService.sendVerification` hands the verification event to Kafka inside the transaction that issues the token. So the email and its link can arrive before the token commits, and a link clicked in that window gets 400. It hit Alice in an earlier run and Dave in this one. Not fixed. The fix is to publish after commit.
- **Product race 2: transaction-service (seen once).** On a wrong OTP, `blockAccountAndCompensate` publishes `fraud.detected`, and account-service blocks the account asynchronously. The refund (`creditBalance`) follows, and it can land after the block. In one run the "sender refunded" check read the wrong balance, and in the next it passed. In 3c.3 the refund becomes a ledger release, which removes this path.

### 8.28 Slice 3c.3: transfers move money through the ledger (2026-11-04)

- **Built** (`38124a0`, then `d8db64f`). A transfer checks both accounts are ACTIVE, saves its row (PROCESSING), and holds the amount: DR sender, CR TRANSFER_IN_FLIGHT (`tx-{id}-hold`). Settle (a clean fraud result, or a correct OTP) posts DR TRANSFER_IN_FLIGHT, CR receiver (`tx-{id}-settle`), and only for a PROCESSING or PENDING_VERIFICATION transfer. Release (an expired or wrong OTP) posts DR TRANSFER_IN_FLIGHT, CR sender (`tx-{id}-release`). A refused hold marks the row FAILED with the reason.
- **Removed:** the old `PUT /api/v1/accounts/{n}/deduct` and `…/credit`, and the `transaction.completed` consumer that credited the receiver. The refund no longer races the account block, because the ledger does not check status.
- **Tests.** transaction-service 16/16 (TransferLedgerTest: the hold and settle with their keys and lines; insufficient balance fails the row with the reason; a blocked account is refused before any row or entry; a completed transfer is not settled again; a wrong OTP releases the hold with its own key). account-service 112/112.
- **Throwaway run on `d8db64f`**: 108 passed, 1 failed, out of 109. Reconciliation: **clean, 0 issues** (the drift from 8.27 is gone). The one failure is Dave's resend link: the auth-service race from 8.27, not yet fixed.
- **Two harness expectations corrected in the same run.** A USER call to the removed credit path gets 403 from the gateway's admin rule, which is the original expectation. The ledger's insufficient-funds detail now starts with "Insufficient funds", as the API's wording does.

### 8.29 Evidence before fixes: the verify-email 400s, and the 3c.4 gate (2026-11-04)

- **Evidence harness** (`4f608e1`). `smoke-throwaway.sh` keeps the auth-service and notification-service logs, and a snapshot of `auth_db.auth_tokens` (hashes only), before every teardown. `smoke-test.sh` writes one row per verify call: whether the token was empty and its length, the SHA-256 of the token (not the raw token), the status and body, and three times: the request that sent the email, the email's log line, and the verify call.
- **Run on `4f608e1`** (product code as `88d94a5`): 108 passed, 1 failed. The failure was Dave's resend link. Answers: (a) the token was not empty: 43 characters. (b) yes: the token was in `auth_tokens`, committed at 19:32:16.1, and revoked at 19:32:19.4 by the resend. (c) verify rejected it because it was revoked, which is correct. The smoke took the first link logged after the resend's mark. That was Dave's first, superseded email: the notification log lagged it by about 6 s, and the resend email arrived 2.6 s after the smoke had already taken the stale link. So the fault was in the harness. Verify was right.
- **Fix** (`a368139`): the smoke waits for Dave's first email to be logged before it starts the resend. Verify is unchanged.
- **`88d94a5` is kept.** The event goes out after the token row commits. In this run each publish came about a second after its token was written (Dave's: token 19:32:16.1, published 19:32:17.2). That is consistent with the fix, but no run before it has been traced, so this does not prove it removed the early-click 400s seen in 8.27.
- **The run on `88d94a5` itself** (52 passed, 43 failed) predates the trace, and its logs were not kept. Its cause is **unexplained**. It is the reason the gate run below is not yet a reliability claim.
- **Product note.** A resend revokes the earlier verification links (`revokeAll`), so an older email's link now shows "Verification failed". That is by design. It is worth one line in the product copy.
- **Release to a blocked sender** (`9839723`, no code change). transaction-service: the release is posted with the sender already BLOCKED. account-service: hold, block the account, release. The sender is refunded, TRANSFER_IN_FLIGHT returns to its starting balance, and reconciliation is clean. The current code blocks on the first wrong OTP, so the "third attempt" test belongs to 3c.5 (D6).
- **Gate run on `9839723`**: 109 passed, 0 failed, out of 109. Reconciliation: clean. Teardown: clean. The dev volumes are unchanged, and the dev stack stayed stopped. The gate for 3c.5 is met.

### 8.30 Slice 3c.5: hashed OTP, three attempts, notification.otp (2026-11-04)

- **Built** (`22ffb19`). The code comes from `SecureRandom`. Redis holds `HMAC-SHA256(internal service secret, "otp|" + transactionId + "|" + code)` and never the code. The key is the existing `INTERNAL_JWT_SECRET`, so no new config was added. The decision to make: a keyed hash of a six-digit code is only useful if the key stays out of Redis, and the internal secret does. Say if you want a dedicated key.
- **Attempts.** The first and second wrong codes get **400** (`Incorrect OTP. N attempts left`), and the transfer stays PENDING_VERIFICATION. The third flags it, refunds the sender and blocks the account, as before. The code and the count are both removed after any outcome.
- **Events.** The code goes only on `notification.otp`, a new topic with one-hour retention (`OtpTopicConfig`). `transaction.otp.generated` gets no new events. Its notification-service listener stays for one release, and both listeners share one send path.
- **Breaking** (README). A wrong OTP is no longer FLAGGED on the first attempt. The smoke test's wrong-OTP check became the three-attempt sequence, with the 400 messages checked.
- **Tests.** transaction-service 25/25 with `OtpTest` (5): the stored value is a hash and is sent once on `notification.otp`; two wrong codes wait and the third flags, refunds and blocks; a correct code after two wrong ones completes; no code appears on any other topic or in any log line.
- **Throwaway run on `22ffb19`**: 111 passed, 0 failed, 0 skipped. Reconciliation: clean. Teardown: clean. The dev volumes are unchanged. The skip count is zero, so the code really was read from `notification.otp`.

### 8.31 Slice 3c.6: Idempotency-Key on transfers (2026-11-04)

- **Built** (`ebb2390`). `POST /api/v1/transactions` requires `Idempotency-Key`. The record is `idempotency_record` (V4), with a lease, as in account-service. The transfer's id is written into the record in the same local transaction that saves the transfer. So a retry after a crash resumes the transfer the first attempt made, and makes no second one (`IdempotencyTest`, the crash case).
- **Responses.** The same key and body gives the first result, with no money moved. The same key with a different body gives **422**. A repeat during the first attempt's lease gives **409**. A missing key gives **400**. A refusal is stored as a 422 and replayed as one. A failure before any transfer is saved releases the key at once.
- **Not built, against 3.6:** the Redis fast path. The record is the only store, as in account-service. Redis is not on the correctness path, so this is an optimization left for later. Record it as a deliberate deviation until it is built.
- **Breaking** (README). The header is required. The smoke test sends a key on every transfer and checks replay, a different body, and a missing key. The Postman transfer request sends one.
- **Tests.** transaction-service 31/31 on a clean run. One earlier run had five `TransactionAuthTest` errors from a Kafka container that timed out at startup. The class passed 5/5 when run alone, and the full suite then passed 31/31.
- **Throwaway run on `ebb2390`**: 114 passed, 0 failed, 0 skipped. Reconciliation: clean. Teardown: clean. The dev volumes are unchanged.

### 8.32 Slice 3c.7: the PIN client waits for phase 4 (2026-11-05)

- **Scope change** (`7b02b71`, plan only). The Feign client to wallet's `POST /internal/v1/pin/verify` is not built in 3c.7. That contract belongs to wallet-service in phase 4, which starts with `threat-model-pin.md`. Building the client now would mean guessing the contract. (This entry was missing from this log; it was added on 2026-11-05 from the plan text.)
- **What 3c.7 builds instead:** the `PinVerifier` port, the gate, and a default that fails closed with 503 while `TRANSFER_PIN_REQUIRED` is on. The phase 4 client replaces the default.
- **Status.** The 3c.7 code is in the working tree and is not in any commit yet.

### 8.33 Slice 3c.5 review: the OTP key, counted guesses, one decision per code (2026-11-05)

- **Approved in the user's review of 3c.5.** A dedicated `OTP_HMAC_KEY`, required at startup, at least 32 bytes, for transaction-service only. An atomic attempt count before the compare. Single use. The throwaway smoke on the final commit. The working rules, now in `bankend/CLAUDE.md`.
- **Evidence first.** Before the fix (`7b02b71`, which includes 3c.6), two new tests in `OtpTest` failed.
  - 10 parallel wrong codes on one transfer: two got a 400 and the third flagged, as expected. But the parallel test logged eight block-and-refund passes where one was expected. None of the seven extra requests got a 409.
  - The same correct code sent twice in parallel: the second call was not refused with a 409.
  - Cause: `verifyOTP` compared the code first and counted a wrong guess after it. Nothing claimed the outcome, so every request that passed the status check acted on it.
- **Fix** (`a1fda8d`).
  - `OtpCodes` takes `OTP_HMAC_KEY` (`otp.hmac-key`) and refuses to start with a key under 32 bytes. The error does not show the key. `compose.yaml` requires it (`:?`), so `compose up` stops until `.env` has it.
  - Each wrong guess takes its count with an atomic `INCR` before the compare. A guess past the third place is refused without a compare, so at most three codes are ever compared. Under a burst the Redis counter can read above 3. Only the first three are compared.
  - One decision per code: `verification:otp-decided:{id}`, set with `SETNX`. A correct code, the third wrong one, and the expiry each claim it. The losers get a 409. The key has the code's five-minute TTL. A new code clears any old decision.
- **Tests.** `OtpTest` 8/8: the two race tests, and the key rule. The full transaction-service suite on the staged tree of `a1fda8d` ran 34 tests. 15 errored at context start, because the Kafka container timed out (four timeouts in the log). Those three classes passed 15/15 when run alone. The dev app containers were running during that run. `TransactionAuthTest` passed in the full run.
- **Not covered.** A crash between the claim and the transfer's save leaves the transfer `PENDING_VERIFICATION` with no code. The code before this change had the same window. I have not checked whether the recovery job covers this state.
- **Throwaway smoke: not run yet.** `scripts/smoke-throwaway.sh a1fda8d` stopped at `compose up`: host port 8083 was already bound by the dev `auth-service`. The dev app containers are crash-looping at up to 99% CPU, because their MySQL, Redis and Kafka containers are gone. The smoke volumes were removed, and the dev volumes are unchanged. The result goes here once the dev app containers are stopped and the run is done.
- **Count.** The smoke count on this commit should be 114, not 111. The 111 was on `22ffb19`, and 3c.6 added three checks (8.31).
- **Config.** `.env` (gitignored) needs `OTP_HMAC_KEY` before `compose up`. It was not added. The README has the breaking-change line.
- **Still uncommitted.** The 3c.7 PIN code, and the transfer PIN lines in `TransactionalService.java` and `application.yaml`. Also the user's Paystack and `INTERNAL_JWT_SECRET` lines in `.env.example`, and the 3c.7 README line. None of these is in `a1fda8d`.
