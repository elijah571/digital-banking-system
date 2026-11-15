# Security Testing Report

## Scope

Read-only repository audit of Spring Security, gateway routing, account/transaction ownership, ledger posting, password/session flows, provider callbacks, Compose topology, and CI. One concrete issue found: auth-service relied on gateway-injected `X-User-Id`/`X-User-Role` on user/admin endpoints while its service-level public chain permitted all requests. This has been changed to validate user JWTs inside auth-service and derive principal/role from verified claims.

## Tests

Focused auth-service tests passed with Testcontainers for forged identity headers, authenticated profile access, and user/admin route authorization. Fireblocks verifier tests passed for official environment JWKS URL allow-listing and malformed detached signatures. `git diff --check` and production Compose configuration validation also passed. No comprehensive penetration test or ZAP run has been performed.

## Not tested

No production target, external provider credentials, Cloudflare account, or authorized penetration-test scope was provided. No live DDoS, destructive test, backup restore, dependency vulnerability scan, or load benchmark has been run.

## Open items

MFA/passkeys, device risk, multi-person approval, gateway/service direct-origin testing, dependency/SBOM scanning, Kafka auth/TLS, security headers, and restore validation remain follow-up work.
