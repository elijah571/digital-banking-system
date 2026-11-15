# Webhook Security

## Current provider behavior

- Stripe: SDK verifies the `Stripe-Signature` against the exact request body and configured endpoint secret. Events are deduplicated and durably outboxed before acknowledgement.
- Flutterwave: `verif-hash` is compared in constant time to the configured provider secret; accepted callbacks are deduplicated and outboxed. Wallet-service fetches provider status before settlement.
- Fireblocks: Webhook V2 detached JWS is verified with RS512 against an environment-matched official JWKS endpoint. Workspace ID is required and matched. The event ID is unique in the durable inbox; processing fetches current transaction state.

Provider signature schemes differ; never normalize them into a home-grown shared scheme. Never credit from an unsigned callback or callback body alone.

## Replay and failure behavior

Durable unique event IDs make a replay a no-op. An unavailable database must produce a retryable non-2xx response. Provider status is re-read for payout/crypto state changes. Fireblocks V2 events can arrive out of order; handlers use current provider state, not arrival order.

## Remaining work

Add signed-payload test fixtures and replay tests for all provider paths. Add visible retry-age/attempt metrics and a controlled parking/replay tool for permanently failing inbox events. Never silently mark an event processed after a financial posting failure.
