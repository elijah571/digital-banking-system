# Architecture

> **Status:** Living document · **Last updated:** 2026-11-15 · **Owner:** Elijah

This document covers how the Digital Banking System is put together, the reasoning behind the main decisions, and how the services talk to each other. For setup and usage, see the [README](README.md).

## Table of Contents

1. [Goals and Constraints](#1-goals-and-constraints)
2. [System Context](#2-system-context)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Components](#4-components)
5. [Data Architecture](#5-data-architecture)
6. [Key Flows](#6-key-flows)
7. [Events and Reliability](#7-events-and-reliability)
8. [Security](#8-security)
9. [Infrastructure and Deployment](#9-infrastructure-and-deployment)
10. [Observability](#10-observability)
11. [Architecture Decision Records](#11-architecture-decision-records)
12. [Known Limitations and Roadmap](#12-known-limitations-and-roadmap)

---

## 1. Goals and Constraints

**Goals**

- Keep money correct under concurrency, retries, and partial failure
- Give each service a clear boundary and let it deploy on its own
- Keep accounting auditable on an append-only double-entry ledger
- Get exactly-once business effects across services without distributed transactions

**Constraints**

- Small project, so I stayed with tools I know well: Spring, MySQL, Kafka, Redis
- Single region at launch
- Has to integrate with outside payment providers (Stripe, Flutterwave), a custody provider, and an email provider
- NGN account transfers do not perform currency conversion; supported crypto assets use a separate integer-base-unit subledger
- Custodial crypto is disabled until Fireblocks credentials and supported assets are explicitly configured

**Non-goals**

- Currency conversion in customer transfers (left for later)
- Multi-region active-active (left for later)

## 2. System Context

Who and what talks to the system.

```text
        ┌────────────────────────────┐
        │ Customer  ·  Administrator │
        └────────────────────────────┘
                       │ users
                       ▼
        ┌────────────────────────────┐
        │ React Web Client           │
        │ Customer + Admin Console   │
        └────────────────────────────┘
                       │ HTTPS, user JWT
                       ▼
        ┌────────────────────────────┐
        │        API Gateway         │
        └────────────────────────────┘
                       │
                       ▼
        ┌────────────────────────────┐
        │      Banking Services      │
        └────────────────────────────┘
                       │
           ┌───────────┬────────────┬─────────────┐
           ▼                        ▼
 ┌──────────────────────┐ ┌───────────────────┐
 │ Stripe / Flutterwave │ │    Email / SMTP   │
 └──────────────────────┘ └───────────────────┘
                                      ▲
                                      │ signed API / Webhook V2
                                      ▼
                            ┌──────────────────────┐
                            │ Fireblocks custody   │
                            └──────────────────────┘
```

## 3. High-Level Architecture

```text
                                       ┌──────────────────────────────────────┐
                                       │  React Web Client (Vite)              │
                                       └──────────────────────────────────────┘
                                                           │ HTTPS, user JWT
                                                           ▼
                                       ┌──────────────────────────────────────┐
                                       │          API Gateway :8080           │
                                       └──────────────────────────────────────┘
                                                           │
                                                           │
           ┌───────────────────────────────┬───────────────┴───────────────┬───────────────────────────────┐
           ▼                               ▼                               ▼                               ▼
┌─────────────────────┐         ┌─────────────────────┐ ledger  ┌─────────────────────┐ PIN /   ┌─────────────────────┐
│     auth-service    │         │   account-service   │◀────────│ transaction-service │────────▶│    wallet-service   │
│        :8083        │         │        :8081        │postings │        :8082        │ routing │        :8087        │
└─────────────────────┘         └─────────────────────┘         └─────────────────────┘         └─────────────────────┘
                                           ▲                                                         │          │
                                           │                                                         │          │
                                           └─────────────────────────────────────────────────────────┘          │
                                                    ledger postings (wallet to account)                         │
                                                                                              intents / payouts │
                                                                                                                │
                                                                                                                ▼
                                                                                                ┌─────────────────────┐
                                                                                                │   payment-service   │
                                                                                                │        :8085        │
                                                                                                └─────────────────────┘
                                                                                                                ▲
                                                                                                  cards / banks │
                                                                                                                ▼
                                                                                                ┌────────────────────────┐
                                                                                                │  Stripe / Flutterwave  │
                                                                                                └────────────────────────┘

Storage (each service owns its own MySQL schema)

auth-service         ─┐
account-service      ─┤
transaction-service  ─┼───────▶ MySQL
wallet-service       ─┤
payment-service      ─┤
crypto-service       ─┘

Short-lived state
API Gateway          ─┐
transaction-service  ─┼───────▶ Redis
wallet-service       ─┘

Fireblocks custody   ◀───────▶ crypto-service :8088

Events (transactional outbox, at-least-once)

account-service      ─┐
transaction-service  ─┤
wallet-service       ─┼── outbox ──▶ Kafka ─┬──▶ fraud-detection-service :8084
payment-service      ─┤                     └──▶ notification-service :8086
crypto-service       ─┘
```

The services live in one monorepo. They call each other synchronously over authenticated HTTP and pass events asynchronously over Kafka. MySQL is the durable store. Redis only holds short-lived, bounded state. Nothing depends on a distributed database transaction: each change is atomic in its own database, and the transactional outbox plus idempotent recovery coordinate the rest.

The browser application lives in `frontend/` and uses React, Vite, and Redux Toolkit Query. It provides customer banking flows and role-aware administration screens in one web client. It calls the public API through the gateway and does not connect directly to MySQL, Kafka, Redis, or provider APIs. There is no native mobile client in this repository.

## 4. Components

| Component               | Path                      | Responsibility                                                      | Tech                      |
| ----------------------- | ------------------------- | ------------------------------------------------------------------- | ------------------------- |
| Web client              | `../frontend`             | Customer banking and admin operations UI; calls the public gateway | React, Vite, Redux Toolkit Query |
| API Gateway             | `API-GATEWAY`             | Public routing, JWT validation, role checks, rate limiting, CORS    | Spring Cloud Gateway      |
| auth-service            | `auth-service`            | Users, credentials, email verification, JWT/refresh-token lifecycle | Spring Boot, MySQL        |
| account-service         | `account-service`         | Accounts, double-entry ledger, journal, statements, reconciliation  | Spring Boot, MySQL        |
| transaction-service     | `transaction-service`     | Internal transfer lifecycle, holds, OTP decisions, recovery         | Spring Boot, MySQL        |
| wallet-service          | `wallet-service`          | PIN, deposits, payouts, name enquiry, daily usage, idempotency      | Spring Boot, MySQL, Redis |
| payment-service         | `payment-service`         | Provider adapters, signed webhooks, provider event records          | Spring Boot, MySQL        |
| crypto-service          | `crypto-service`          | Fireblocks vaults and addresses, crypto deposits/withdrawals, provider reconciliation | Spring Boot, MySQL, Kafka |
| fraud-detection-service | `fraud-detection-service` | Risk-rule evaluation and short-lived velocity signals               | Spring Boot, Redis        |
| notification-service    | `notification-service`    | Email, OTP delivery, transaction alerts                             | Spring Boot               |
| banking-common          | `libs/banking-common`     | Shared types, security helpers, event envelopes                     | Java library              |

For a transfer to another bank, `transaction-service` validates the user's name-enquiry reference through wallet-service, then routes the payout request through `wallet-service → payment-service`; wallet-service calls account-service for the hold and settlement ledger entries. Transfers to our own bank continue through the internal transfer saga. Nothing calls back into transaction-service, so there is no cycle.

Withdrawals and external transfers share `outbound_payout` and one state machine. The default provider is a deterministic simulator; Flutterwave is selectable for NGN bank payouts. Provider webhooks are signature-checked and written to payment-service's outbox. Wallet treats each event as a prompt to fetch provider status and checks the returned reference, amount, and currency before changing payout state or posting ledger entries.

### 4.1 Service layering

Each service follows `controller → service → repository → entity`. Controllers never touch money state directly; the service layer owns the business logic and the transaction boundary. Requests are validated at the controller, and a shared error handler returns a consistent error shape.

### 4.2 account-service: the ledger

`account-service` owns the double-entry ledger. It is the only service allowed to write `ledger_account.balance`, `journal_entry`, or `journal_line`. Everything else asks for a posting through an internal API. A posting checks that amounts are positive and that debits and credits balance per currency, locks every touched ledger account in ascending ID order, checks the idempotency key under those locks, enforces the non-negative-wallet and one-time-reversal rules, then writes the entry and commits. Rows are append-only, so a correction is a reversing entry rather than an edit, and the journal always reconciles.

Crypto balances also belong to account-service, in separate `crypto_ledger_account`, `crypto_journal_entry`, and `crypto_journal_line` tables. Each asset balances independently in integer smallest units; crypto identifiers never pass through Java `Currency` or the NGN decimal ledger. `crypto-service` can request only `DEPOSIT_CREDIT`, `WITHDRAWAL_RESERVE`, `WITHDRAWAL_SETTLE`, and `WITHDRAWAL_RELEASE` postings. Crypto-service owns provider workflow state, while account-service remains the sole writer of customer asset balances.

`crypto-service` creates Fireblocks vault accounts per customer and provisions an address per asset using the configured account or derived-address mode. Withdrawals verify the wallet PIN, run crypto-specific fraud screening, enforce the asset's daily limit, reserve the amount in account-service, and submit with a stable external transaction ID. Signed Webhook V2 requests are durably recorded before asynchronous processing; the service fetches current provider status and polls uncertain submissions instead of blindly resending them. Confirmed deposits can require administrator review before credit.

### 4.3 Identity verification and account upgrades

Customers submit identity details, a document image, consent, and a prompted face-verification video through the web client. Account-service stores the submission and presents pending evidence in the administrator review screen. An authorized administrator records an approval or rejection; account-tier changes are applied by the backend after approval. This is a manual-review workflow: the application does not run biometric matching, automated liveness scoring, sanctions screening, or third-party identity verification. The uploaded images and videos are sensitive personal data and require strict access control, protected storage and backups, a defined retention period, and deletion procedures.

### 4.4 Asynchronous services

`fraud-detection-service` reads transfer and payout events, looks up the balance information it is allowed to see, and publishes a decision. `notification-service` reads auth, OTP, and transaction events and sends email, without ever owning money state. Both dedupe on event ID, which is what makes at-least-once delivery safe.

## 5. Data Architecture

### 5.1 Entity Relationship Overview

```text
┌────────────────────┐                  ┌────────────────────────┐              ┌────────────────────────┐
│        USER        │                  │        ACCOUNT         │              │     LEDGER_ACCOUNT     │
├────────────────────┤                  ├────────────────────────┤              ├────────────────────────┤
│ id (PK)            │      owns        │ id (PK)                │   maps to    │ id (PK)                │
│ email              │1────────────────*│ user_id (FK)           │1────────────1│ type                   │
│ role               │                  │ account_number         │              │ balance                │
│ created_at         │                  │ status                 │              │ currency               │
└───┬────────────────┘                  │ currency               │              └────────────────────────┘
    │                                   └────────────────────────┘                           │
    │                                                                                        │ 1  debited / credited
    │   ┌────────────────────────┐                                                           │
    ├──▶│ PIN_CREDENTIAL (0..1)  │                                                           ▼ *
    │   └────────────────────────┘      ┌────────────────────────┐              ┌────────────────────────┐
    │                                   │     JOURNAL_ENTRY      │              │      JOURNAL_LINE      │
    │   ┌────────────────────────┐      ├────────────────────────┤              ├────────────────────────┤
    └──▶│ OUTBOUND_PAYOUT (0..*) │      │ id (PK)                │  contains    │ id (PK)                │
        └────────────────────────┘      │ idempotency_key        │1────────────*│ entry_id (FK)          │
                                        │ reversal_of            │              │ ledger_account_id (FK) │
                                        │ posted_at              │              │ debit                  │
                                        └────────────────────────┘              │ credit                 │
                                                                                └────────────────────────┘
```

### 5.2 Conventions

- UUID primary keys, with `created_at` and `updated_at` on every table
- Each service owns its own MySQL schema; no service reads another's tables
- NGN money is stored as decimals and sent over the wire as scaled strings (for example `"1250.00"`); crypto amounts use integer base units and are rendered as decimal strings only at API boundaries
- The journal is append-only, so corrections are compensating entries
- Schema changes go through Flyway migrations only; Hibernate validates the schema and never changes it

### 5.3 Redis Usage

| Purpose              | Key pattern         | TTL         |
| -------------------- | ------------------- | ----------- |
| Rate limiting        | `rl:{ip}:{route}`   | 1 minute    |
| Hashed OTP           | `otp:{userId}`      | short-lived |
| Fraud velocity       | `velocity:{userId}` | short-lived |
| Idempotency response | `idem:{key}`        | minutes     |

The durable idempotency records and ledger keys stay in MySQL, so flushing Redis can never let a duplicate payment through. Any security check that needs short-lived state fails closed if that state is missing.

## 6. Key Flows

### 6.1 Authentication

```text
     Client              API Gateway          auth-service              MySQL                 Redis
        │                     │                     │                     │                     │
        │ POST /api/v1/auth/login (email, password) │                     │                     │
        │────────────────────▶│                     │                     │                     │
        │                     │                     │                     │                     │
        │                     │ forward (rate-limited)                    │                     │
        │                     │────────────────────▶│                     │                     │
        │                     │                     │                     │                     │
        │                     │                     │ find user, verify password hash           │
        │                     │                     │────────────────────▶│                     │
        │                     │                     │                     │                     │
        │                     │                     │ user record         │                     │
        │                     │                     │◀╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌│                     │
        │                     │                     │                     │                     │
        │                     │                     │ store refresh-token state                 │
        │                     │                     │──────────────────────────────────────────▶│
        │                     │                     │                     │                     │
        │ access token (short-lived) + rotating refresh token             │                     │
        │◀╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌│                     │                     │
        │                     │                     │                     │                     │
```

### 6.2 Internal Transfer (hold, fraud check, settle)

```text
     Client        transaction-service   wallet-service      account-service          Kafka      fraud-detection-service
        │                   │                   │                   │                   │                   │
        │ POST /api/v1/transactions (idempotency key, PIN)          │                   │                   │
        │──────────────────▶│                   │                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │ verify PIN        │                   │                   │                   │
        │                   │──────────────────▶│                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │ PIN OK            │                   │                   │                   │
        │                   │◀╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌│                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │ post hold (debit sender -> TRANSFER_IN_FLIGHT)            │                   │
        │                   │──────────────────────────────────────▶│                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │ publish transfer.initiated            │                   │                   │
        │                   │──────────────────────────────────────────────────────────▶│                   │
        │                   │                   │                   │                   │                   │
        │ 202 Accepted      │                   │                   │                   │                   │
        │◀╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌│                   │                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │                   │                   │                   │ transfer.initiated│
        │                   │                   │                   │                   │──────────────────▶│
        │                   │                   │                   │                   │                   │
  ── alt: clean ──          │                   │                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │ low risk          │                   │                   │                   │
        │                   │◀──────────────────────────────────────────────────────────────────────────────│
        │                   │                   │                   │                   │                   │
        │                   │ settle (TRANSFER_IN_FLIGHT -> receiver wallet)            │                   │
        │                   │──────────────────────────────────────▶│                   │                   │
        │                   │                   │                   │                   │                   │
  ── else: suspicious ──    │                   │                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │ high risk, require OTP                │                   │                   │
        │                   │◀──────────────────────────────────────────────────────────────────────────────│
        │                   │                   │                   │                   │                   │
        │ POST /{id}/verify (OTP)               │                   │                   │                   │
        │──────────────────▶│                   │                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │ settle on success, or release hold on failure/expiry      │                   │
        │                   │──────────────────────────────────────▶│                   │                   │
        │                   │                   │                   │                   │                   │
```

### 6.3 Card Deposit (Stripe, signed webhook)

```text
     Client          wallet-service      payment-service         Stripe               Kafka          account-service
        │                   │                   │                   │                   │                   │
        │ POST deposit (idempotency key)        │                   │                   │                   │
        │──────────────────▶│                   │                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │ create PaymentIntent                  │                   │                   │
        │                   │──────────────────▶│                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │ client secret     │                   │                   │                   │                   │
        │◀╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌│                   │                   │                   │
        │                   │                   │                   │                   │                   │
        │ confirm card payment                  │                   │                   │                   │
        │──────────────────────────────────────────────────────────▶│                   │                   │
        │                   │                   │                   │                   │                   │
        │                   │                   │ signed webhook    │                   │                   │
        │                   │                   │◀──────────────────│                   │                   │
        │                   │                   │                   │                   │                   │
  ── payment-service: verify signature, dedupe event, write to outbox ──                │                   │
        │                   │                   │                   │                   │                   │
        │                   │ payment.succeeded │                   │                   │                   │
        │                   │◀──────────────────────────────────────────────────────────│                   │
        │                   │                   │                   │                   │                   │
        │                   │ post deposit (provider clearing -> customer wallet)       │                   │
        │                   │──────────────────────────────────────────────────────────────────────────────▶│
        │                   │                   │                   │                   │                   │
```

### 6.4 Custodial Crypto (Fireblocks)

```text
Customer       API Gateway       crypto-service       wallet / fraud       account-service       Fireblocks
   │                 │                  │                    │                    │                   │
   │ request address │                  │                    │                    │                   │
   ├────────────────▶├─────────────────▶│ create vault + asset address                              │
   │                 │                  ├───────────────────────────────────────────────────────────▶│
   │                 │                  │◀──────────────────────── address ─────────────────────────┤
   │◀────────────────┴──────────────────┤                    │                    │                   │
   │                                     │                    │                    │                   │
   │ send crypto to address              │                    │                    │                   │
   │                                     │◀──────── signed Fireblocks Webhook V2 ────────────────────┤
   │                                     │ persist inbox; fetch current provider state                │
   │                                     ├───────────────────────────────────────────────────────────▶│
   │                                     │ confirmed + optional manual review                         │
   │                                     ├───────────────────────────────────▶│ DEPOSIT_CREDIT      │
   │                                     │                    │                    │                   │
   │ submit withdrawal + PIN             │                    │                    │                   │
   ├────────────────▶├─────────────────▶├── verify PIN ─────▶│                    │                   │
   │                 │                  ├── fraud check ─────▶│                    │                   │
   │                 │                  ├───────────────────────────────────▶│ WITHDRAWAL_RESERVE │
   │                 │                  ├── idempotent withdrawal request ───────────────────────────▶│
   │                 │                  │◀────────────────── current status / webhook ────────────────┤
   │                 │                  ├───────────────────────────────────▶│ SETTLE or RELEASE  │
```

Deposit credit and withdrawal settlement/release are idempotent account-service postings. Crypto-service stores provider events before acknowledging webhooks, processes them asynchronously, and polls by external transaction ID when submission status is uncertain. Admin review is available for confirmed deposits and risk-flagged withdrawals.

## 7. Events and Reliability

### Event naming

`<entity>.<past-tense-verb>`, for example `transfer.initiated`, `payment.succeeded`, `payout.settled`.

### Event envelope

```json
{
  "id": "evt_01HXYZ",
  "type": "transfer.initiated",
  "version": 1,
  "occurredAt": "2026-11-06T21:00:00Z",
  "correlationId": "cor_123",
  "payload": { "transferId": "txf_456", "accountNumber": "0123456789" }
}
```

### Delivery guarantees

Every service that emits events writes an outbox row in the same transaction as the business change, and a relay publishes that row to Kafka after the commit. Delivery is at-least-once, so consumers dedupe on `id` and keep their transitions idempotent. Payloads carry a `version`, and a breaking change bumps it. The `correlationId` travels through HTTP and Kafka, which is what lets a single request be traced from the API call through the ledger posting, the provider webhook, and the notification.

### Recovery

Recovery is built in rather than done by hand:

- Transfer recovery finishes or releases a stale hold from the durable decision
- Deposit expiry cancels the provider intent before it expires the wallet state
- Payout recovery checks provider status before it retries an external send
- Crypto recovery retries idempotent ledger reservations, polls uncertain Fireblocks submissions by external transaction ID, and processes durable Webhook V2 inbox events
- Reconciliation spots any drift between a cached balance and the journal

## 8. Security

| Concern          | Approach                                                                   |
| ---------------- | -------------------------------------------------------------------------- |
| Authentication   | Short-lived JWT access tokens with single-use, rotating refresh tokens     |
| Authorization    | JWT and role checks at gateway/service boundaries; ownership re-checked in the responsible service |
| Internal APIs    | Short-lived signed service tokens carrying caller, audience, allow-list    |
| Transport        | TLS at the public edge; service and infra ports stay private in prod       |
| Secrets          | Environment variables from a secrets manager, never in git                 |
| Input            | Validation at the edge, parameterized queries only                         |
| PIN / OTP        | Argon2id-hashed PIN with progressive lockout; OTPs hashed and never logged |
| Abuse protection | Rate limiting per IP and per user                                          |
| Auditing         | Append-only audit records for sensitive actions and money changes          |
| Fireblocks       | Server-side RS256 API request JWTs; detached RS512 Webhook V2 signatures checked against the configured environment JWKS |
| KYC evidence     | Documents and face videos are reviewed by authorized administrators and treated as restricted identity data |

Only `transaction-service`, `wallet-service`, and `crypto-service` can call their explicitly scoped internal ledger operations, and no service trusts an unverified `X-User-*` header for identity. Auth-service validates JWTs locally on authenticated profile and user-administration routes. Fireblocks credentials stay on the server. The webhook route is public at the gateway but verifies the signature and configured workspace before persisting an event. Manual document review is not a substitute for a jurisdiction-specific KYC/AML compliance program.

### Staff RBAC

Staff access uses seven roles: `ROLE_SUPER_ADMIN`, `ROLE_COMPLIANCE_OFFICER`, `ROLE_TREASURY_APPROVER`, `ROLE_FINANCE_RECON_ADMIN`, `ROLE_RISK_ANALYST`, `ROLE_CUSTOMER_SUPPORT`, and `ROLE_AUDITOR`. Auth-service adds the staff role and its resolved permission strings to signed JWT claims. The shared catalog and JWT converter live in `banking-common`; the gateway and downstream services check permissions on supported admin routes. The React console also filters navigation and write controls from the authenticated user permissions.

Auditor requests are restricted to `GET`, `HEAD`, and `OPTIONS` at the gateway and in the account, auth, crypto, payment, transaction, and wallet services. Withdrawal and crypto review services prevent a requester from approving their own operation. Staff access changes are written to auth-service's append-only audit log and revoke refresh tokens. Access JWTs already issued remain usable until their short expiry.

The current role storage is an enum on `users` with a shared code permission map. The normalized role and permission tables in the RBAC proposal are not implemented. `risk.alerts.resolve`, `reconciliation.resolve`, global limits management, and provider webhook inspection do not yet have operational API workflows. See [Banking Security Architecture](docs/engineering/BANKING_SECURITY_ARCHITECTURE.md) for the detailed permission matrix and implementation limits.

## 9. Infrastructure and Deployment

```text
┌───────────┐  push    ┌──────────┐          ┌──────────────────┐  images    ┌──────────────────────┐
│ Developer │─────────▶│  GitHub  │─────────▶│ CI: build, test  │───────────▶│  Container Registry  │
└───────────┘          └──────────┘          └──────────────────┘            └──────────────────────┘
                                                                                         │
                                                                                         │
                                                                               ┌─────────┴────────────┐
                                                                               ▼                      ▼
                                                                        ┌────────────┐        ┌──────────────┐
                                                                        │  Staging   │        │  Production  │
                                                                        └────────────┘        └──────────────┘
```

- **Runtime:** Docker containers run with Docker Compose (`compose.yaml` locally, `compose.prod.yaml` in production)
- **Environments:** `development` (local Compose), `staging`, `production`
- **Migrations:** Flyway runs additively on startup, Hibernate only validates
- **Crypto provider:** crypto-service runs on `:8088`; production publishes only the gateway. Fireblocks is disabled by default and requires provider credentials, workspace/JWKS settings, and supported asset configuration
- **Production hardening:** only the gateway is public, Swagger is off, actuator is limited, resource limits are set, logs rotate, containers restart on failure
- **Web client:** build with `npm run build` in `frontend/`; set `VITE_API_URL` to the gateway origin and optionally `VITE_STRIPE_PUBLISHABLE_KEY`. Vite variables are public and must never contain secret credentials.
- **Local configuration:** copy `bankend/.env.example` to `bankend/.env`. It contains development placeholders for Compose-required bank identifiers, payout limits, and PIN protection secrets. Replace all placeholder secrets before use.
- **Rollback:** follow the release rollback procedure and reconcile ledger state before restoring customer traffic

## 10. Observability

- **Request tracking:** gateway request IDs and service logs support request correlation; verify production redaction and retention settings.
- **Health checks:** services expose actuator health endpoints for Compose health checks; production configuration limits actuator exposure.
- **Metrics and tracing:** central metrics, distributed tracing, alerting, and Kafka/outbox dashboards require deployment configuration and are not supplied by the local Compose stack.
- **Reconciliation:** account-service provides ledger reconciliation operations; configure monitoring and alerting around reconciliation results in the target environment.

## 11. Architecture Decision Records

Short notes on the decisions that shaped the design. The full set is in [`bankend/docs/adr/`](bankend/docs/adr/).

### ADR-001: Single-writer double-entry ledger

- **Status:** Accepted
- **Context:** Several services move money, but balances have to stay provably correct and auditable.
- **Decision:** `account-service` is the only writer of the journal and balances. Every other service asks for a posting through an internal API.
- **Consequences:** The balance, locking, and idempotency rules live in one place. The cost is an internal call on every money movement.

### ADR-002: Transactional outbox over Kafka

- **Status:** Accepted
- **Context:** Cross-service effects have to survive a crash without a distributed transaction.
- **Decision:** Write the event to an outbox in the same transaction as the business change, relay it to Kafka, and dedupe by event ID on the consumer.
- **Consequences:** Business effects happen exactly once even though transport is at-least-once. This needs idempotent consumers and a relay process.

### ADR-003: Ordered locking and durable idempotency keys

- **Status:** Accepted
- **Context:** Concurrent postings and client or provider retries can race or double up a payment.
- **Decision:** Lock ledger accounts in ascending ID order, and gate every posting on a durable MySQL idempotency key. See [`bankend/docs/adr/0001-locking.md`](bankend/docs/adr/0001-locking.md).
- **Consequences:** The lock order rules out deadlocks, and the key rules out duplicate movements even if Redis is flushed.

### ADR-004: Separate integer-base-unit crypto subledger

- **Status:** Accepted
- **Context:** NGN uses fixed-scale currency values, while crypto assets have per-asset precision and are not Java `Currency` identifiers. Provider callbacks and withdrawal retries also need durable replay protection.
- **Decision:** Keep customer asset balances in account-service, but store crypto accounts and append-only journal lines in separate tables using integer base units. crypto-service owns the Fireblocks workflow and can invoke only four scoped crypto posting operations.
- **Consequences:** Existing NGN ledger behavior remains unchanged, crypto precision is exact, and account-service remains the sole balance writer. Asset configuration must use the correct network precision, fee asset, confirmation threshold, and limits.

## 12. Known Limitations and Roadmap

**Limitations**

- The client is a web application; no native mobile client is included.
- KYC is reviewed manually; automated biometrics, liveness scoring, and third-party identity screening are not implemented.
- Local Compose runs a single Kafka broker with plaintext listeners and replication factor one; it is not production high availability.
- Single-region deployment
- NGN only, with no currency conversion in a transfer
- Live inter-bank payouts depend on a third-party provider being up
- Custodial crypto requires Fireblocks credentials, matching webhook workspace/JWKS configuration, and administrator-reviewed asset/network setup; live Fireblocks transactions have not been verified without provider credentials
- Production backup restoration, disaster-recovery objectives, load targets, and external edge/WAF controls must be configured and validated for the deployment environment

**Roadmap**

- [ ] Add read replicas for statement and reporting queries
- [ ] Add distributed tracing (OpenTelemetry) across the services
- [ ] Expand automated load testing of the money path in CI
- [ ] Define and implement production KYC evidence encryption, retention, and deletion controls
