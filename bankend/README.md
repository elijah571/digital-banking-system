# Digital Banking System

Spring Boot 4 / Java 25 microservices, wired together with Kafka, MySQL and Redis.

| Service | Port | What it does |
|---|---|---|
| API-GATEWAY | 8080 | Single entry point: JWT validation, roles, rate limiting, combined Swagger UI |
| auth-service | 8083 | Register, email verification, login (JWT), refresh, password reset, user admin |
| account-service | 8081 | Accounts, balances, block/credit/deduct |
| transaction-service | 8082 | Transfers (saga), OTP verification |
| fraud-detection-service | 8084 | Velocity / amount / balance checks on every transfer |
| payment-service | 8085 | Stripe payment intents + webhook |
| notification-service | 8086 | Sends auth emails via Gmail SMTP; logs transaction alerts |

Transfer flow: `POST /transactions` → sender debited → `transaction.initiated` → fraud check →
clean: `transaction.completed` → receiver credited · suspicious: OTP issued → `POST /transactions/{id}/verify`.

## 1. Configure secrets

```bash
cp .env.example .env   # then fill in the values below
```

| Variable | What |
|---|---|
| `JWT_SECRET` | ≥ 32 random bytes, shared by auth-service and the gateway — `openssl rand -base64 48` |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | First admin, created on auth-service's first start |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_MAIL` / `SMTP_PASSWORD` | Gmail SMTP (465 = SSL, 587 = STARTTLS) + **Google App Password** (see below). Empty → emails are logged instead |
| `STRIPE_*` | Stripe test keys |

`.env` is gitignored. Every other setting has a working default.

**Gmail SMTP:** normal Gmail passwords are rejected by `smtp.gmail.com`. Turn on 2-Step Verification on the
Google account, then create an App Password at https://myaccount.google.com/apppasswords and use that
16-character value as `SMTP_PASSWORD`. Restart notification-service after changing it
(`docker compose up -d notification-service`).

## Authentication & roles

```
POST /api/v1/auth/register  →  email with link  →  GET /api/v1/auth/verify-email?token=…
POST /api/v1/auth/login     →  { accessToken (JWT, 15 min), refreshToken (7 days) }
Authorization: Bearer <accessToken>   on every other request
POST /api/v1/auth/refresh   →  new pair (refresh tokens are single-use; reuse logs the user out everywhere)
POST /api/v1/auth/forgot-password → email with reset token → POST /api/v1/auth/reset-password
```

| | USER | ADMIN |
|---|---|---|
| Open an account, see **own** accounts / balance / history | ✅ | ✅ (any account) |
| Transfer, verify OTP, create payment — **own accounts only** | ✅ | ✅ (own accounts only) |
| List all accounts / transactions | — | ✅ |
| Block account, credit / deduct balance | — | ✅ |
| List users, promote/demote, enable/disable (`/api/v1/users`) | — | ✅ |

How it works: auth-service signs an HS256 JWT (`sub` = user id, `role`, `email`). The gateway verifies it,
enforces the role rules, removes any client-sent `X-User-*` headers and adds `X-User-Id / X-User-Email /
X-User-Role` from the token. Each service uses those headers for ownership checks.

> ⚠️ The services trust those headers, so they must only be reachable through the gateway.
> `compose.prod.yaml` does this. In dev mode ports 8081-8086 are published for debugging — a request sent
> directly to them (bypassing :8080) is treated as an internal call and is **not** authenticated.

## 2. Run

**Everything in Docker** (only Docker needed — images are built with multi-stage Dockerfiles, no local JDK):
```bash
docker compose up -d --build --wait   # builds all images, waits until every healthcheck is green
docker compose ps
docker compose logs -f account-service
```
Each container has a healthcheck (`/actuator/health`), and services start only after their dependencies are healthy.
Database schemas are managed by **Flyway** (`<service>/src/main/resources/db/migration`) and applied on startup;
Hibernate only validates them (`ddl-auto: validate`). Databases created before Flyway are baselined at V1 automatically.

**Or infrastructure in Docker, services from your IDE / terminal (fast dev loop):**
```bash
docker compose up -d mysql redis kafka
./scripts/build.sh                     # installs libs/banking-common (shared code) + builds every service
account-service/mvnw -f account-service/pom.xml spring-boot:run     # repeat for each service
```

**Tests** (need Docker: integration tests run on real MySQL / Kafka / Redis via Testcontainers):
```bash
./scripts/build.sh --test              # shared lib + every service: unit, integration, ArchUnit
account-service/mvnw -f account-service/pom.xml verify             # one service (install libs/banking-common first)
```
ArchUnit rules (`libs/banking-common`, `MoneyArchRules`) fail the build if `double`/`float` appear in money code,
if `BigDecimal` is converted to/from `double`, or if `BigDecimal.equals` is used instead of `compareTo`.
CI (`.github/workflows/ci.yml`, at the repository root) runs all of this plus `compose up` + the smoke test on every push.

## 3. Test the endpoints

| Tool | How |
|---|---|
| Automated, all endpoints | `./scripts/smoke-test.sh` (Docker) · `NOTIFICATION_LOG=/tmp/notification-service.log ./scripts/smoke-test.sh` (mvnw) |
| Postman | Import `postman/digital-banking.postman_collection.json`, run folders top to bottom |
| Swagger UI (all services) | http://localhost:8080/swagger-ui.html — pick Accounts / Transactions / Payments in the top-right dropdown; "Try it out" goes through the gateway |
| OpenAPI spec files | `docs/openapi/*.json` (import into Postman, Insomnia, or a client generator) |
| Health | http://localhost:8080/actuator/health (each service has `/actuator/health`) |

In Swagger: run **Auth → POST /login**, copy `accessToken`, click **Authorize**, paste it. It is kept across
the Accounts / Transactions / Payments pages.

The OTP for suspicious transfers is only written to the notification-service log:
`docker compose logs notification-service | grep OTP`. In dev mode verification links and reset tokens are
logged too (`MAIL_LOG_CONTENT=true`); production turns this off.

Stripe webhook locally: `stripe listen --forward-to localhost:8080/api/v1/payments/webhook`, put the printed
`whsec_...` into `STRIPE_WEBHOOK_SECRET`, restart payment-service, then `stripe trigger payment_intent.succeeded`.

## 4. Deploy

The stack is fully containerised, so any Linux host with Docker works (VPS such as DigitalOcean / Hetzner /
AWS EC2 / Lightsail; ≥ 4 GB RAM recommended for 6 JVMs + Kafka + MySQL):

```bash
git clone <repo> && cd digital-banking-system
cp .env.example .env && nano .env      # strong MYSQL_ROOT_PASSWORD, Stripe keys
docker compose -f compose.yaml -f compose.prod.yaml up -d
BASE_URL=http://<server-ip>:8080 ./scripts/smoke-test.sh
```

`compose.prod.yaml` publishes **only the gateway (8080)**, keeps MySQL/Redis/Kafka and the internal services
on the private Docker network, disables Swagger, limits actuator to `health,info`, sets memory limits,
rotates logs and restarts containers automatically (including after a reboot).

Still to do before real users:
- Put HTTPS in front of 8080 (Caddy / Nginx / a cloud load balancer).
- Set a strong `MYSQL_ROOT_PASSWORD` **before the first start** (MySQL only reads it when the volume is created).
- Point the Stripe dashboard webhook at `https://<your-domain>/api/v1/payments/webhook`.

## Breaking changes

One line per breaking change, newest first. Each change adds its line in the same commit as the change.

- **transaction-service needs `OTP_HMAC_KEY`** (phase 3, slice 3c.5 review, wallet-plan 3c.5). Set it in `.env` before `compose up`, because compose stops without it. It must be at least 32 bytes. It is used only for the hash of a transfer OTP, and it is not the internal service secret.
- **`POST /api/v1/transactions` requires an `Idempotency-Key` header** (phase 3, slice 3c.6, wallet-plan 3.6). A missing key gets 400. The same key with the same body returns the first result and moves no money. The same key with a different body gets 422. A repeat while the first attempt is still running gets 409. A retry after a crash resumes the transfer the first attempt saved.
- **A wrong transfer OTP no longer flags the transfer on the first attempt** (phase 3, slice 3c.5, D6). The first and second wrong OTPs get **400** (`Incorrect OTP. N attempts left`), and the transfer stays PENDING_VERIFICATION. The third flags it, refunds the sender and blocks the account, as before. OTPs are kept hashed in Redis. Their events go on `notification.otp`, and `transaction.otp.generated` gets no new events.
- **Admin credit and deduct moved to `/api/v1/admin/accounts/{n}/credit` and `…/deduct`** (`b8b4e0e`). They take `amount`, `reason` and an `Idempotency-Key` header, with an ADMIN token. The old `PUT /api/v1/accounts/{n}/credit` and `…/deduct` no longer accept admin or user tokens. Only transaction-service may call them, and they are removed in phase 3, slice 3c.
- **The old `PUT /api/v1/accounts/{n}/deduct` and `…/credit` are removed** (phase 3, slice 3c.3). Transfers now move money through the ledger: the sender's amount is held in `TRANSFER_IN_FLIGHT`, then settled to the receiver or released back. Admin adjustments use `/api/v1/admin/accounts/{n}/credit|deduct`. A call to the old paths gets 404.
- **Opening an account creates its wallet, and `initialDeposit` goes through the ledger** (phase 3, wallet-at-opening). `POST /api/v1/accounts` with a non-zero `initialDeposit` gets 400 unless `ACCOUNT_OPENING_DEPOSIT_ENABLED` is true, which is the dev setting. Prod leaves it off, so accounts open at 0 and are funded by a deposit. A zero or omitted deposit is accepted.
- **transaction-service and payment-service require a user token on every `/api` route** (phase 3, slice 3c.2, D8). Their `X-User-*` headers no longer identify anyone. Calls through the gateway are unchanged, because the gateway forwards the `Authorization` header. A direct call to port 8082 or 8085 now needs `Authorization: Bearer <user token>`.
- **The legacy `X-User-*` header path is removed from account-service** (`8b8155f`). Headers no longer identify anyone there. `GET /api/v1/accounts/{n}`, `…/balance`, `PUT …/deduct` and `PUT …/credit` need a token.

## Error responses

Errors use standard `application/problem+json` bodies, e.g.
`{"status":422,"title":"Unprocessable Content","detail":"Insufficient funds for account 1234567890"}`.

| Status | When |
|---|---|
| 400 | Validation failed, weak password, invalid/expired email token, bad Stripe webhook signature |
| 401 | Missing / invalid / expired token, wrong password |
| 403 | Not your account / transaction, admin-only endpoint, email not verified, user disabled |
| 404 | Account / transaction not found |
| 409 | Duplicate email, OTP verified twice, admin demoting themselves |
| 422 | Insufficient funds, account not active |
| 502 | Stripe or account-service unavailable |
