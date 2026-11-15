# Circuit Breaker Configuration

## Current status

The repository has no Resilience4j circuit-breaker or bulkhead integration configured. A circuit breaker must not be claimed as active merely because an HTTP client has a timeout. Important payment, PIN, fraud, account, and Fireblocks client calls currently rely on their caller's error handling and durable recovery.

## Safe rollout policy

Before enabling a breaker on a money path, define its fallback explicitly:

| Dependency | Outage behavior |
| --- | --- |
| Account-service ledger | Fail closed; retain durable pending state and retry with the same key |
| PIN verifier | Fail closed; do not create or submit withdrawal |
| Fraud screening | Fail closed or hold for review; never silently treat unavailable as low risk |
| Fireblocks create transaction | Mark submission uncertain; reconcile by stable external transaction ID before retry |
| Read-only bank list | Use a bounded cache if freshness/expiry policy is defined |

Use bounded connect/read timeouts, a small sliding window, measured failure thresholds, a cooldown, and a bulkhead sized to the dependency's capacity. Never return a successful financial result from a breaker fallback. Any implementation must include tests for open, half-open, recovery, and idempotent retry behavior.

## Outstanding

No breaker configuration is active yet. Add the library and per-client configuration in a separate reviewed implementation after selecting policies and confirming Spring Boot 4 compatibility.
