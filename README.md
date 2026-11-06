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

This is the backend for a digital bank, built as eight Spring Boot services behind a single API gateway. It covers the parts of banking that move money: registration and login, NGN accounts, wallet-to-wallet transfers behind a PIN, card top-ups through Stripe, and withdrawals or transfers out to other banks.

Balances are not a number that each service edits. Every amount comes from a double-entry ledger owned by one service, which keeps a full, append-only history of each movement. The system is built around the problems a real bank has to get right. A retried request must not move money twice. Two transfers hitting the same account at once must not corrupt a balance. And a payment that a provider confirms late must still land exactly once.

> For the design in detail, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Features

- **Double-entry ledger.** One service owns the journal and is the only writer. Entries are append-only, so a mistake is fixed with a reversing entry rather than an edit and the history always reconciles.
- **Auth and roles.** Email verification, short-lived JWT access tokens, single-use refresh tokens that rotate on use, password reset, and admin user management.
- **PIN-gated transfers.** Internal transfers need a PIN hashed with Argon2id, and the lockout gets stricter after repeated failures. Transfers the fraud checks flag require a one-time code before they settle.
- **Card deposits.** Card payments go through Stripe and are confirmed over signed webhooks. The same webhook is only ever applied once, even when Stripe retries it.
- **Withdrawals and bank transfers.** Withdrawals and transfers to other banks share one outbound pipeline, using Paystack in production and a deterministic simulator in tests.
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
| Payments       | Stripe (cards), Paystack + simulator (bank payouts)  |
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
    ├── payment-service/          # Stripe + Paystack adapters, webhooks, provider events (:8085)
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
- Provider credentials, only for the flows that call Stripe, Paystack, or SMTP

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
| `PAYSTACK_SECRET_KEY`                                  | Paystack credential for the bank-provider adapter   |    No    |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_MAIL`, `SMTP_PASSWORD` | Outbound email (Gmail needs an App Password)        |    No    |

> **Never commit `.env` files.** See [.env.example](bankend/.env.example) for the full list; in production these come from a secrets manager, never from git.

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
```

The money paths get the most coverage: concurrent transfers on one account, duplicate requests, duplicate provider webhooks, recovery of stuck work, and a check that the cached balances still match the journal.

## Deployment

Production runs the same services under Docker Compose, with only the gateway exposed.

```bash
./scripts/build.sh
docker compose -f compose.prod.yaml up -d
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
| GET    | `/api/v1/accounts/{accountNumber}/balance`    | Account balance                   | Yes  |
| POST   | `/api/v1/transactions`                        | Start an internal transfer        | Yes  |
| POST   | `/api/v1/transactions/{transactionId}/verify` | Verify OTP for a flagged transfer | Yes  |
| POST   | `/api/v1/payments/create-order`               | Create a card-deposit order       | Yes  |
| POST   | `/api/v1/payments/webhook`                    | Provider webhook (signed)         | Sig  |

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

</details>

## License

MIT. See [LICENSE](LICENSE).

## Maintainers

- **Elijah**: [@elijah571](https://github.com/elijah571)
