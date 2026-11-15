# Digital Banking System

**Backend for a digital bank: accounts, wallets, transfers, card deposits, and bank payouts, all settled through a double-entry ledger.**

[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange)](#)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4-6DB33F)](#)
[![Services](https://img.shields.io/badge/services-8-informational)](#)

[Documentation](bankend/docs/) · [Architecture](ARCHITECTURE.md) · [Report a Bug](https://github.com/elijah571/digital-banking-system/issues) · [Request a Feature](https://github.com/elijah571/digital-banking-system/issues)

</div>

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Usage](#usage)
- [Testing](#testing)
- [Deployment](#deployment)
- [API Reference](#api-reference)
- [Contributing](#contributing)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## Overview

This is the backend for a digital bank, built as nine Spring Boot services behind a single API gateway. It covers the parts of banking that move money: registration and login, NGN accounts, wallet-to-wallet transfers behind a PIN, card top-ups through Stripe, withdrawals or transfers out to other banks, and Fireblocks-backed custodial crypto wallets.

Balances are not a number that each service edits. Every amount comes from a double-entry ledger owned by one service, which keeps a full, append-only history of each movement. The system is built around the problems a real bank has to get right. A retried request must not move money twice. Two transfers hitting the same account at once must not corrupt a balance. And a payment that a provider confirms late must still land exactly once.

> For the design in detail, see [ARCHITECTURE.md](ARCHITECTURE.md).

Engineering security and operations guides are collected in [`docs/engineering/`](docs/engineering/), including ledger integrity, idempotency, webhook security, Kafka reliability, incident response, and disaster recovery.

## Features

- **Double-entry ledger.** One service owns the journal and is the only writer. Entries are append-only, so a mistake is fixed with a reversing entry rather than an edit and the history always reconciles.
- **Auth and roles.** Email verification, short-lived JWT access tokens, single-use refresh tokens that rotate on use, password reset, and admin user management.
- **PIN-gated transfers.** Internal transfers need a PIN hashed with Argon2id, and the lockout gets stricter after repeated failures. Transfers the fraud checks flag require a one-time code before they settle.
- **Card deposits.** Card payments go through Stripe and are confirmed over signed webhooks. The same webhook is only ever applied once, even when Stripe retries it.
- **Withdrawals and bank transfers.** Withdrawals and transfers to other banks share one outbound pipeline. The deterministic simulator is the default; Flutterwave handles NGN bank payouts when selected.
- **Fraud checks and notifications.** A separate service scores transfers on velocity and amount signals. Email and OTP delivery run off events so they never block a payment.
- **Recovery and audit.** Idempotency keys, a transactional outbox, scheduled reconciliation, and an audit record for every money change.

## Tech Stack

| Layer          | Technology                                           |
| -------------- | ---------------------------------------------------- |
| Language       | Java 25                                              |
| Framework      | Spring Boot 4, Spring Cloud Gateway                  |
| Build          | Maven (per-service Maven Wrapper)                    |
| Database       | MySQL (per-service schema), Flyway migrations        |
| Messaging      | Apache Kafka (domain events, transactional outbox)   |
| Cache          | Redis (rate limits, OTP, fraud signals, idempotency) |
| Payments       | Stripe (cards), simulated or Flutterwave bank payouts |
| Infrastructure | Docker Compose                                       |
| API Docs       | OpenAPI / Swagger UI                                 |

## Project Structure

```text
digital-banking-system/
├── README.md
├── ARCHITECTURE.md
└── bankend/
    ├── API-GATEWAY/              # Public edge: JWT validation, routing, rate limiting   (:8080)
    ├── auth-service/             # Identity, JWT/refresh tokens, password recovery       (:8083)
    ├── account-service/          # Accounts + double-entry ledger, journal, reconcile    (:8081)
    ├── transaction-service/      # Internal transfer orchestration, OTP decisions        (:8082)
    ├── wallet-service/           # PIN, deposits, payouts, name enquiry, idempotency     (:8087)
    ├── crypto-service/           # Fireblocks custody, crypto addresses, withdrawals       (:8088)
    ├── payment-service/          # Stripe + Flutterwave adapters, webhooks, provider events (:8085)
    ├── fraud-detection-service/  # Risk scoring from transfer/payout events              (:8084)
    ├── notification-service/     # Email, OTP delivery, transaction alerts               (:8086)
    ├── libs/banking-common/      # Shared types, security, and event envelopes
    ├── docs/                     # Ledger model, ADRs, threat model, OpenAPI snapshots
    ├── scripts/                  # build.sh, smoke-test.sh
    ├── compose.yaml              # Local stack (services + MySQL, Kafka, Redis)
    └── compose.prod.yaml         # Production stack (gateway-only exposure)
```

## Getting Started

### Prerequisites

- JDK 25
- Docker and Docker Compose
- `curl` and `jq` for the HTTP smoke test
- Provider credentials, only for the flows that call Stripe, Flutterwave, or SMTP

### Installation

```bash
# 1. Clone the repository
git clone https://github.com/elijah571/digital-banking-system.git
cd digital-banking-system/bankend

# 2. Create your environment file
cp .env.example .env
# edit .env and set your own values (see Configuration)

# 3. Build all service jars and Docker images
./scripts/build.sh

# 4. Start the full stack (services + MySQL, Kafka, Redis)
docker compose up -d
docker compose ps
```

Once the containers report healthy, everything is reachable through the gateway:

| Service     | URL                                   |
| ----------- | ------------------------------------- |
| API Gateway | http://localhost:8080                 |
| Health      | http://localhost:8080/actuator/health |
| API Docs    | http://localhost:8080/swagger-ui.html |

## Configuration

Configuration comes from environment variables. Copy `.env.example` to `.env` and set your own values.

| Variable                                               | Description                                         | Required |
| ------------------------------------------------------ | --------------------------------------------------- | :------: |
| `MYSQL_ROOT_PASSWORD`                                  | Local MySQL root password (set before first volume) |   Yes    |
| `JWT_SECRET`                                           | Signing secret for user JWTs (32+ random bytes)     |   Yes    |
| `INTERNAL_JWT_SECRET`                                  | Signing secret for service-to-service tokens        |   Yes    |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD`                        | Initial administrator account                       |   Yes    |
| `PIN_PEPPER`, `OTP_HMAC_KEY`                           | Secret material for wallet PIN and OTP protection   |   Yes    |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`           | Stripe credentials for card deposits                |    No    |
| `FLUTTERWAVE_SECRET_KEY`                                | Flutterwave credential when `PAYOUT_PROVIDER=flutterwave` (test key outside `prod`) |    No    |
| `FLUTTERWAVE_WEBHOOK_SECRET`                            | Flutterwave dashboard secret hash; checked against the `verif-hash` header |    No    |
| `FLUTTERWAVE_ENCRYPTION_KEY`                            | Flutterwave encryption key; not used for NGN bank transfers |    No    |
| `FLUTTERWAVE_BASE_URL`                                  | Flutterwave V3 API base URL; defaults to `https://api.flutterwave.com/v3` |    No    |
| `ACCOUNT_VERIFIED_DAILY_LIMIT`                          | Daily limit after successful BVN upgrade; defaults to `5000000.00` NGN | No |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_MAIL`, `SMTP_PASSWORD` | Outbound email (Gmail needs an App Password)        |    No    |
| `WITHDRAWAL_MIN`, `WITHDRAWAL_MAX`                     | Per-withdrawal NGN bounds                            |   Yes    |
| `WITHDRAWAL_DAILY_MAX`                                  | Maximum outbound NGN amount per account per day      |   Yes    |
| `WITHDRAWAL_APPROVAL_THRESHOLD`                         | Amount at or above which an admin must approve      |   Yes    |
| `OUTBOUND_FEE`                                           | Flat NGN payout fee; defaults to `0.00`              |    No    |
| `PAYOUT_PROVIDER`                                        | `simulator` (default) or `flutterwave`               |    No    |
| `PAYOUT_SIM_DEFAULT_OUTCOME`                             | Simulator outcome for ordinary account numbers: `success`, `failure` or `pending`; defaults to `success` | No |
| `PAYOUT_SIM_DELAY_MS`                                    | Simulator delay for send/status calls; defaults to `0` ms | No |

> **Never commit `.env` files.** See [.env.example](bankend/.env.example) for the full list; in production these come from a secrets manager, never from git.

### Withdrawal and external transfer release notes

The withdrawal endpoints are additive; no existing route contract changes. `POST /api/v1/wallet/withdrawals` requires a user token, a verified PIN, a valid bank name-enquiry reference, and an `Idempotency-Key`. It returns `202` with the payout id and current state. A payout at or above the configured approval threshold, or one the fraud check cannot clear, waits for an administrator. The provider result is read asynchronously; the wallet settles only after a provider status of `SUCCESS`.

`POST /api/v1/transactions` keeps internal transfers on the existing ledger saga. Set `bankCode` to another bank and include the `nameEnquiryRef` returned by `POST /api/v1/transfers/name-enquiry` to route the request through the wallet payout pipeline. External transfers share the withdrawal limits, PIN check, fraud gate, maker-checker flow, recovery, and idempotency rules. See [deployment configuration](bankend/docs/deploy.md) before enabling Flutterwave.

## Usage

### Common commands

| Command                                  | Description                                     |
| ---------------------------------------- | ----------------------------------------------- |
| `./scripts/build.sh`                     | Build all service jars and Docker images        |
| `./scripts/build.sh --jars`              | Build jars only, skip the Docker images         |
| `docker compose up -d`                   | Start the full local stack                      |
| `docker compose up -d mysql redis kafka` | Start infrastructure only (run services in IDE) |
| `docker compose ps`                      | Show container status and health                |
| `docker compose down`                    | Stop the stack (named volumes are kept)         |
| `./scripts/smoke-test.sh`                | Run the end-to-end gateway smoke test           |

To iterate on one service against local infrastructure:

```bash
docker compose up -d mysql redis kafka
./account-service/mvnw -f account-service/pom.xml spring-boot:run
```

### Example request

Log in with the administrator account you set as `ADMIN_EMAIL` and `ADMIN_PASSWORD` in `.env`:

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "<ADMIN_EMAIL>", "password": "<ADMIN_PASSWORD>"}'
```

## Testing

Each service has its own tests, run with its Maven wrapper. Integration tests spin up throwaway MySQL, Kafka, and Redis with Testcontainers, so they never touch your local database.

```bash
# Unit + integration tests for one service
./account-service/mvnw -f account-service/pom.xml test

# End-to-end money path through the gateway
./scripts/smoke-test.sh

# Full throwaway stack, including 50 parallel withdrawals and reconciliation
./scripts/smoke-throwaway.sh
```

The money paths get the most coverage: concurrent transfers and withdrawals, withdrawal PIN lockout, duplicate requests and provider webhooks, payout recovery, and checks that the cached balances and journal reconcile.

## Deployment

Production runs the same services under Docker Compose, with only the gateway exposed.

```bash
./scripts/build.sh
docker compose -f compose.yaml -f compose.prod.yaml up -d
```

| Branch      | Environment | Trigger        |
| ----------- | ----------- | -------------- |
| `feature/*` | Review / PR | Pull request   |
| `main`      | Production  | Tagged release |

The production profile keeps service and infrastructure ports private, turns off Swagger, limits actuator exposure, sets resource limits, and restarts containers on failure. It also expects HTTPS at the edge, strong database and signing secrets, configured provider webhooks, and a tested database backup.

## API Reference

Interactive docs are served at [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html) when the gateway is up. Per-service OpenAPI snapshots are in [`bankend/docs/openapi/`](bankend/docs/openapi/). The table below lists the key endpoints; Swagger and the snapshots have the complete set.

| Method | Endpoint                                      | Description                       | Auth |
| ------ | --------------------------------------------- | --------------------------------- | :--: |
| POST   | `/api/v1/auth/register`                       | Register a user                   |  No  |
| POST   | `/api/v1/auth/login`                          | Authenticate, issue tokens        |  No  |
| POST   | `/api/v1/auth/refresh`                        | Rotate the refresh token          |  No  |
| GET    | `/api/v1/auth/me`                             | Current user                      | Yes  |
| POST   | `/api/v1/accounts/create`                     | Open an account                   | Yes  |
| GET    | `/api/v1/accounts/me`                         | List the caller's accounts        | Yes  |
| GET    | `/api/v1/accounts/verification`               | Read the caller's verification and account-tier status | User |
| POST   | `/api/v1/accounts/verification/identity`      | Submit an identity document and prompted face video for manual review | User |
| POST   | `/api/v1/accounts/verification/upgrade`       | Submit account-upgrade details and evidence for manual review | User |
| GET    | `/api/v1/accounts/verification/admin/reviews` | List pending KYC reviews with submitted evidence | Admin |
| POST   | `/api/v1/accounts/verification/admin/reviews/{ownerId}` | Approve or reject an identity or upgrade review | Admin |
| GET    | `/api/v1/accounts/{accountNumber}/balance`    | Account balance                   | Yes  |
| POST   | `/api/v1/transactions`                        | Start an internal or external bank transfer | Yes |
| POST   | `/api/v1/transactions/{transactionId}/verify` | Verify OTP for a flagged transfer | Yes  |
| POST   | `/api/v1/transfers/name-enquiry`               | Resolve a destination bank account | Yes |
| GET    | `/api/v1/banks`                                | List supported banks              | Yes  |
| POST   | `/api/v1/wallet/withdrawals`                   | Request an idempotent withdrawal  | Yes  |
| GET    | `/api/v1/wallet/payouts/{id}`                  | Read payout status (owner or ADMIN) | Yes |
| POST   | `/api/v1/wallet/withdrawals/{id}/cancel`        | Cancel a withdrawal awaiting approval | Yes |
| POST   | `/api/v1/wallet/withdrawals/{id}/approve`       | Approve a pending withdrawal      | Admin |
| POST   | `/api/v1/wallet/withdrawals/{id}/reject`        | Reject a pending withdrawal       | Admin |
| POST   | `/api/v1/wallet/deposits`                      | Start an idempotent card deposit | Yes  |
| GET    | `/api/v1/wallet/deposits/{depositId}`          | Read a card deposit's status     | Yes  |
| POST   | `/api/v1/payments/create-order`                | Deprecated legacy payment order | Yes  |
| POST   | `/api/v1/payments/webhook`                     | Provider webhook (signed)        | Sig  |

Identity submissions require a user token, `documentType`, a base64 JPEG/PNG `documentImage`, a base64 WebM `faceVideo` (up to 20 MB), and consent. Account-upgrade submissions include BVN, name, residential address, address document type and image, the prompted face video, and consent. Both requests stay pending until an administrator views the evidence and approves or rejects them. Approving an account upgrade promotes the customer's owned accounts. Evidence is stored in the account service database and is available to administrators through the KYC review screen; configure database access, backups, and retention to protect this sensitive data.

## Contributing

1. Fork the repo and branch off `main`: `git checkout -b feat/your-feature`
2. Use [Conventional Commits](https://www.conventionalcommits.org): `feat: add payout recovery job`
3. Keep each change to one logical slice, and make sure the build and tests pass
4. Open a pull request against `main`

## Troubleshooting

<details>
<summary><strong>Port already in use</strong></summary>

A service port (`8080`–`8087`) or an infrastructure port (MySQL `3306`, Redis `6379`, Kafka `9092`) is taken. Stop the process using it, or remap the port in `compose.yaml`.

```bash
lsof -i :8080
```

</details>

<details>
<summary><strong>Database connection refused</strong></summary>

Check that the containers are up and healthy with `docker compose ps`. Also confirm `MYSQL_ROOT_PASSWORD` in `.env` matches the value used when the MySQL volume was first created; changing it later does not rotate the stored credential.

</details>

<details>
<summary><strong>Calls return 401 or 403</strong></summary>

Public calls go through the gateway on `:8080`, not directly to a service port. Check that your access token is fresh (they expire quickly) and that `JWT_SECRET` and `INTERNAL_JWT_SECRET` are set the same way across services.

### Custodial crypto

The crypto service is disabled by default: `FIREBLOCKS_ENABLED=false`, and no crypto assets are enabled until an administrator configures them. This prevents an unconfigured or sandbox deployment from accepting deposits or withdrawals. Configure the Fireblocks API key, private key, webhook workspace ID and JWKS endpoint in the server environment; never place those credentials in the frontend. Use Fireblocks Sandbox first, then enable production credentials and network-specific assets deliberately.

Customers use the Crypto page for deposit addresses, balances, withdrawal requests, and transaction history. Withdrawals verify the wallet PIN, run the fraud check, reserve integer base units in account-service, enforce an asset daily cap, then submit to Fireblocks with a stable external transaction ID. Account-service owns the crypto subledger and is the sole balance writer. Signed Fireblocks Webhooks V2 events are stored before acknowledgment and reconciled asynchronously; the service also polls uncertain submissions so it does not blindly resubmit a withdrawal. Deposit confirmation counts and manual deposit review are configured per asset. Administrators configure assets and networks and review flagged deposits or withdrawals from Crypto Operations.

The public API is routed through the gateway under `/api/v1/crypto/**`. Admin configuration is `PUT /api/v1/crypto/admin/assets`; review actions are under `/api/v1/crypto/admin/transactions/{id}`. Configure an enabled asset only after its Fireblocks asset ID, network, precision, address format, confirmation threshold, daily limit (integer base units), address mode, and fee asset have been checked for that specific network. For Fireblocks address behavior, `ACCOUNT` uses the vault asset address and `DERIVED` requests a distinct address for each user. Start with manual deposit review enabled.

</details>

## License

MIT. See [LICENSE](LICENSE).

## Maintainers

- **Elijah**: [@elijah571](https://github.com/elijah571)
