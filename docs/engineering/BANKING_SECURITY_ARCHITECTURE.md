# Banking Security Architecture

## Enforced in code

- Spring Security verifies HS256 user JWT issuer, signature, and expiry at the gateway and service-specific security chains. Auth-service now validates its own JWTs and derives user/admin identity from the verified token rather than trusting `X-User-*` headers.
- Internal service tokens use a distinct secret, issuer, audience, SERVICE role, and caller allow-list.
- Account and transaction ownership is checked in their service layer. account-service is the only NGN/crypto ledger writer.
- PINs use an Argon2id/peppered wallet path (see wallet PIN threat model); account passwords currently use BCrypt cost 12.
- Stripe signatures use the Stripe SDK against the raw payload; Flutterwave uses its provider-defined constant-time `verif-hash`; Fireblocks Webhook V2 uses detached RS512/JWKS plus workspace matching.
- Public gateway routes have Redis-backed rate limiting. Sensitive write paths use idempotency and audit/outbox records.

## Known gaps

- No passkey/WebAuthn or general MFA enrollment and recovery flow is implemented. Email verification/reset alone is not a phishing-resistant factor.
- Device registration, new-device alerts, geolocation risk, privileged-access management, and full maker-checker approval are not present across all admin/financial operations.
- mTLS and managed secret rotation are deployment responsibilities, not configured in Compose.
- HSTS, WAF, bot protection, and origin firewalling must be enabled at the public edge.

Do not market the current system as satisfying a banking compliance or certification standard. Close the gaps above with threat-modelled migrations and user/admin recovery design.
