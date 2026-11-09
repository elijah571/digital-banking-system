# Deployment configuration

Compose reads configuration from `bankend/.env`; never commit that file. Keep credentials in a deployment secrets manager in production. The checked-in `.env.example` is user-owned and is not changed by this phase.

## Bank payouts

`PAYOUT_PROVIDER` is shared by wallet-service and payment-service and must have the same value in both:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PAYOUT_PROVIDER` | `simulator` | Select `simulator` or `paystack`. The simulator is the local, test, and CI default. |
| `PAYSTACK_SECRET_KEY` | empty | Required for Paystack. Use an `sk_test_` key outside the `prod` profile and an `sk_live_` key only with the `prod` profile. |
| `PAYSTACK_API_URL` | `https://api.paystack.co` | Paystack API base URL; override only for a controlled test endpoint. |
| `OWN_BANK_CODE` | required | Bank code for this bank. Transfers with this code use the internal transaction flow. |
| `OWN_BANK_NAME` | required | Display name included in the wallet bank list. |
| `WITHDRAWAL_MIN` | required | Minimum outbound amount in NGN. |
| `WITHDRAWAL_MAX` | required | Maximum outbound amount in NGN. |
| `WITHDRAWAL_DAILY_MAX` | required | Shared daily outbound limit per source account. |
| `WITHDRAWAL_APPROVAL_THRESHOLD` | required | Amount at or above which an administrator must approve. |
| `OUTBOUND_FEE` | `0.00` | Flat NGN fee held with a withdrawal or external transfer. |
| `PAYOUT_SIM_DEFAULT_OUTCOME` | `success` | Simulator outcome for ordinary account numbers. |
| `PAYOUT_SIM_DELAY_MS` | `0` | Simulator send/status delay in milliseconds. |

Paystack payouts require an enabled Paystack Transfers product and sufficient provider balance for the transfer and its fee. Transfers stay asynchronous: the wallet settles only after status verification, and a signed webhook is a trigger to verify status. A test key must be used for sandbox checks; test mode does not move live funds. Do not use a live key for smoke tests.

Recipient bank account numbers are sent to Paystack for account resolution and transfer. The payment-service recipient cache stores only a keyed HMAC fingerprint of the bank code/account number pair and Paystack's recipient code. Keep `INTERNAL_JWT_SECRET` stable and protected: it keys those fingerprints as well as service tokens.

## Shared Compose variables

The following variables are read by the Compose files. Required values have no production-safe default; set them in the environment or secrets manager before deployment.

| Variable | Purpose |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | Local MySQL root credential. Use a managed database credential in production. |
| `JWT_SECRET` | User access-token signing and verification. |
| `INTERNAL_JWT_SECRET` | Service-to-service token signing and Paystack recipient fingerprint key. |
| `PIN_PEPPER` | Secret pepper for wallet PIN hashing. |
| `PIN_OTP_HMAC_KEY` | HMAC key for wallet PIN verification codes. |
| `OTP_HMAC_KEY` | HMAC key for transaction OTPs. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Initial administrator credentials. Replace the password after provisioning. |
| `STRIPE_PUBLISHABLE_KEY`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | Stripe card deposit integration. |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_MAIL`, `SMTP_PASSWORD`, `MAIL_FROM` | Outbound email delivery. |
| `MAIL_LOG_CONTENT` | Development-only message logging switch; keep disabled in production. |
| `APP_PUBLIC_URL`, `PASSWORD_RESET_URL` | Public application and password-reset URLs. |
| `CORS_ALLOWED_ORIGINS` | Allowed browser origins. Set explicit origins for production. |
| `GATEWAY_PORT`, `REDIS_HOST_PORT` | Host ports for the gateway and Redis in the development Compose file. |

Money configuration also includes `CARD_DEPOSIT_MIN`, `WITHDRAWAL_MIN`, `WITHDRAWAL_MAX`, `WITHDRAWAL_DAILY_MAX`, `WITHDRAWAL_APPROVAL_THRESHOLD`, and `OUTBOUND_FEE`. Their defaults and service-specific runtime settings are documented in the service `application.yml` files. PIN secrets must be at least 32 bytes.

## Production Compose

Run `docker compose -f compose.yaml -f compose.prod.yaml up -d` after providing the production configuration. The production override enables the `prod` Spring profile, suppresses the internal service host ports, and leaves only the API gateway published. The payment-service rejects a test key under `prod`; use the simulator until the Paystack live integration is explicitly enabled for production.

For local Paystack sandbox development, use `PAYOUT_PROVIDER=paystack` with an `sk_test_` credential and the normal API URL. Start with bank listing and account resolution, then run one small transfer to Paystack's documented test transfer account. Verify the payout through the API and confirm the webhook is delivered to `/webhooks/paystack`. Keep this separate from the throwaway simulator smoke test.
