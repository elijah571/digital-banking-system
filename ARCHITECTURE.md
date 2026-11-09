# Architecture

> **Status:** Living document · **Last updated:** 2026-11-06 · **Owner:** Elijah

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
- Has to integrate with outside payment providers (Stripe, Paystack) and an email provider
- Customer wallets are NGN only; there is no currency conversion inside a transfer

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
        │    Web / Mobile Client     │
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
           ┌───────────┴────────────┐
           ▼                        ▼
 ┌───────────────────┐    ┌───────────────────┐
 │ Stripe / Paystack │    │    Email / SMTP   │
 └───────────────────┘    └───────────────────┘
```

## 3. High-Level Architecture

```text
                                       ┌──────────────────────────────────────┐
                                       │         Web / Mobile Client          │
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
                                                                                                ┌─────────────────────┐
                                                                                                │  Stripe / Paystack  │
                                                                                                └─────────────────────┘

Storage (each service owns its own MySQL schema)

auth-service         ─┐
account-service      ─┤
transaction-service  ─┼───────▶ MySQL
wallet-service       ─┤
payment-service      ─┘

Short-lived state
API Gateway          ─┐
transaction-service  ─┼───────▶ Redis
wallet-service       ─┘

Events (transactional outbox, at-least-once)

account-service      ─┐
transaction-service  ─┤
wallet-service       ─┼── outbox ──▶ Kafka ─┬──▶ fraud-detection-service :8084
payment-service      ─┘                     └──▶ notification-service :8086
```

The services live in one monorepo. They call each other synchronously over authenticated HTTP and pass events asynchronously over Kafka. MySQL is the durable store. Redis only holds short-lived, bounded state. Nothing depends on a distributed database transaction: each change is atomic in its own database, and the transactional outbox plus idempotent recovery coordinate the rest.

## 4. Components

| Component               | Path                      | Responsibility                                                      | Tech                      |
| ----------------------- | ------------------------- | ------------------------------------------------------------------- | ------------------------- |
| API Gateway             | `API-GATEWAY`             | Public routing, JWT validation, role checks, rate limiting, CORS    | Spring Cloud Gateway      |
| auth-service            | `auth-service`            | Users, credentials, email verification, JWT/refresh-token lifecycle | Spring Boot, MySQL        |
| account-service         | `account-service`         | Accounts, double-entry ledger, journal, statements, reconciliation  | Spring Boot, MySQL        |
| transaction-service     | `transaction-service`     | Internal transfer lifecycle, holds, OTP decisions, recovery         | Spring Boot, MySQL        |
| wallet-service          | `wallet-service`          | PIN, deposits, payouts, name enquiry, daily usage, idempotency      | Spring Boot, MySQL, Redis |
| payment-service         | `payment-service`         | Provider adapters, signed webhooks, provider event records          | Spring Boot, MySQL        |
| fraud-detection-service | `fraud-detection-service` | Risk-rule evaluation and short-lived velocity signals               | Spring Boot, Redis        |
| notification-service    | `notification-service`    | Email, OTP delivery, transaction alerts                             | Spring Boot               |
| banking-common          | `libs/banking-common`     | Shared types, security helpers, event envelopes                     | Java library              |

For a transfer to another bank, `transaction-service` validates the user's name-enquiry reference through wallet-service, then routes the payout request through `wallet-service → payment-service`; wallet-service calls account-service for the hold and settlement ledger entries. Transfers to our own bank continue through the internal transfer saga. Nothing calls back into transaction-service, so there is no cycle.

Withdrawals and external transfers share `outbound_payout` and one state machine. The default provider is a deterministic simulator; Paystack is selectable for NGN bank payouts. Provider webhooks are signature-checked and written to payment-service's outbox. Wallet treats each event as a prompt to fetch provider status and checks the returned reference, amount, and currency before changing payout state or posting ledger entries.

### 4.1 Service layering

Each service follows `controller → service → repository → entity`. Controllers never touch money state directly; the service layer owns the business logic and the transaction boundary. Requests are validated at the controller, and a shared error handler returns a consistent error shape.

### 4.2 account-service: the ledger

`account-service` owns the double-entry ledger. It is the only service allowed to write `ledger_account.balance`, `journal_entry`, or `journal_line`. Everything else asks for a posting through an internal API. A posting checks that amounts are positive and that debits and credits balance per currency, locks every touched ledger account in ascending ID order, checks the idempotency key under those locks, enforces the non-negative-wallet and one-time-reversal rules, then writes the entry and commits. Rows are append-only, so a correction is a reversing entry rather than an edit, and the journal always reconciles.

### 4.3 Asynchronous services

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
- Money is stored as NGN decimals and sent over the wire as scaled strings (for example `"1250.00"`); extra precision is rejected rather than rounded
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
- Reconciliation spots any drift between a cached balance and the journal

## 8. Security

| Concern          | Approach                                                                   |
| ---------------- | -------------------------------------------------------------------------- |
| Authentication   | Short-lived JWT access tokens with single-use, rotating refresh tokens     |
| Authorization    | Role checks at the gateway, ownership re-checked inside each service       |
| Internal APIs    | Short-lived signed service tokens carrying caller, audience, allow-list    |
| Transport        | TLS at the public edge; service and infra ports stay private in prod       |
| Secrets          | Environment variables from a secrets manager, never in git                 |
| Input            | Validation at the edge, parameterized queries only                         |
| PIN / OTP        | Argon2id-hashed PIN with progressive lockout; OTPs hashed and never logged |
| Abuse protection | Rate limiting per IP and per user                                          |
| Auditing         | Append-only audit records for sensitive actions and money changes          |

Only `transaction-service` and `wallet-service` can post ledger entries, and no service trusts an unverified `X-User-*` header for identity.

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
- **Production hardening:** only the gateway is public, Swagger is off, actuator is limited, resource limits are set, logs rotate, containers restart on failure
- **Rollback:** redeploy the previous image tag

## 10. Observability

- **Logging:** structured JSON with `correlationId`, `userId`, and request context, with PIN, OTP, and password fields masked
- **Health checks:** `/actuator/health` per service, aggregated at the gateway
- **Metrics:** request rate, error rate, latency percentiles, Kafka consumer lag, outbox backlog
- **Reconciliation:** a scheduled job checks cached balances against the journal and alerts on drift

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

## 12. Known Limitations and Roadmap

**Limitations**

- Single-region deployment
- NGN only, with no currency conversion in a transfer
- Live inter-bank payouts depend on a third-party provider being up

**Roadmap**

- [ ] Add read replicas for statement and reporting queries
- [ ] Add distributed tracing (OpenTelemetry) across the services
- [ ] Expand automated load testing of the money path in CI
