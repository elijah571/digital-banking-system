# Banking Security Architecture

## Authentication and request identity

- Auth-service signs short-lived HS256 access tokens. The gateway and downstream APIs validate the token signature, issuer, and expiry using the shared JWT secret.
- User tokens carry a legacy `role` claim for compatibility, plus signed `roles` and resolved `authorities` claims for staff authorization. `banking-common` owns the role-to-permission catalog and JWT authority converter.
- Existing `ADMIN` accounts continue to map to `ROLE_SUPER_ADMIN` permissions. Newly assigned staff roles receive only the permissions listed for that role.
- Internal service tokens use a different secret, issuer, audience, SERVICE role, and caller allow-list. Customer identity is taken from verified tokens; client-supplied `X-User-*` headers are not trusted.
- Auditor tokens are blocked from non-read HTTP methods at the gateway and in the account, auth, crypto, payment, transaction, and wallet service security chains.

## Staff roles and permission boundaries

| Role | Operational scope |
| --- | --- |
| `ROLE_SUPER_ADMIN` | All catalog permissions, including staff role management and sensitive configuration. |
| `ROLE_COMPLIANCE_OFFICER` | Customer/account reads and freezes, KYC review, transaction and crypto review reads, risk reads, and audit reads. |
| `ROLE_TREASURY_APPROVER` | Payout and crypto operation approvals, account/transaction reads, ledger and reconciliation reads, and audit reads. |
| `ROLE_FINANCE_RECON_ADMIN` | Ledger and reconciliation reads/resolution permission, transaction/payout reads, provider webhook read permission, and audit reads. |
| `ROLE_RISK_ANALYST` | Customer/account reads, account freezes, transaction/payout/crypto reads, risk read/resolve permissions, and audit reads. |
| `ROLE_CUSTOMER_SUPPORT` | Customer, account, transaction, payout, and crypto wallet/transaction reads. |
| `ROLE_AUDITOR` | Read permissions across customers, accounts, KYC, ledger, reconciliation, transactions, payouts, crypto, risk, and audit. Mutating HTTP methods are denied. |

The gateway checks granular authorities for supported administrator routes. Services repeat the checks for KYC review, payout and crypto approval, customer administration, and other service-owned routes. The admin UI also hides routes and actions the signed user profile does not permit; this is a usability layer and is not the authorization boundary.

Payout approval checks that the requesting customer is not the approver. Crypto review decisions also reject a decision by the transaction owner. Both checks are enforced in the service layer.

## Enforced in code

- Account and transaction ownership is checked in the owning service. `account-service` is the only NGN/crypto ledger writer.
- Account passwords use BCrypt cost 12. Wallet PINs use the Argon2id/peppered path; OTPs are hashed and never logged.
- Stripe signatures use the Stripe SDK against the raw payload; Flutterwave's `verif-hash` is compared in constant time; Privy uses Svix HMAC-SHA256 verification. A Fireblocks Webhook V2 RS512/JWKS verifier and workspace check remain in code for legacy provider records, but there is no active Fireblocks webhook controller or gateway route.
- Public gateway routes have Redis-backed rate limiting. Sensitive write paths use idempotency and service audit/outbox records.
- Staff role changes and account enable/disable changes write an append-only auth-service audit row and revoke refresh tokens.
- Identity documents and face videos are restricted identity evidence shown to roles with KYC read or review permission (compliance and auditor). Customer support currently has no KYC endpoint access.
- `crypto-service` is routed by the gateway at `/api/v1/crypto/**`; development Compose publishes port 8088. New EVM deposit addresses and withdrawals use Privy when enabled and when the asset network code is explicitly mapped to a supported EVM chain ID.
- Privy webhook events use Svix HMAC-SHA256 verification with timestamp tolerance and constant-time signature comparison. The crypto controller currently exposes the Privy webhook route.

## Current implementation limits

- The seven staff roles are stored in the existing `users.role` enum and mapped to permissions in `banking-common`; the normalized `admin_roles`, `admin_permissions`, `role_permissions`, and `user_roles` tables from the proposed blueprint are not implemented.
- Role changes revoke refresh tokens. Existing access JWTs remain valid until their short expiry; there is no JTI denylist or access-token version check yet.
- Maker-checker is enforced for withdrawal approval and crypto review ownership. It is not yet a general two-staff approval workflow for ledger overrides, staff role changes, or all high-value money movement.
- `risk.alerts.resolve`, `reconciliation.resolve`, global limits management, provider webhook-log inspection, and some catalog permissions are defined but lack corresponding operational API endpoints. A permission alone does not create that workflow.
- Customer support does not receive `kyc.read`, so it cannot see KYC metadata or media. A separate masked KYC metadata endpoint is still needed to match the support role in the specification.
- No passkey/WebAuthn or general MFA enrollment and recovery flow is implemented. mTLS, managed secret rotation, HSTS, WAF, bot protection, and origin firewalling remain deployment responsibilities.
- Privy is disabled by default. Enabling it requires the app ID and secret, wallet authorization quorum ID and private key, webhook signing secret, and explicit EVM chain mappings. `PRIVY_APP` and `PRIVY_SECRET` are accepted as Compose compatibility aliases for the app ID and secret. The current withdrawal path accepts EVM addresses only.
- Fireblocks support is in transition: the client and status reconciliation code remain for previously recorded legacy provider transactions, but new crypto withdrawals are stored with provider `PRIVY`, and legacy-provider submissions fail closed. Fireblocks Webhook V2 verification code is not connected to an exposed webhook endpoint.

Do not market the current system as satisfying a banking compliance or certification standard. Review jurisdiction-specific KYC/AML obligations and close the implementation limits above with a threat-modelled rollout.
