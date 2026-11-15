# Idempotency Implementation

## Patterns in this repository

- Internal transfers and payouts require a client idempotency key and persist a request fingerprint/result in MySQL.
- Ledger entries have unique durable idempotency keys. Replaying identical content returns the original entry; reusing a key for different lines is a conflict.
- Provider event tables use unique event identifiers. Stripe uses its provider event ID; Flutterwave derives a stable payout event identity from reference and status; Fireblocks uses the V2 notification ID.
- Outbound provider operations use stable references/external transaction IDs and query provider status before uncertain retries.
- Transactional outboxes publish at-least-once; consumers must dedupe on event ID and retain replay-safe state transitions.

## Rules for new money flows

1. Scope caller keys by authenticated user and route/provider.
2. Hash all money-moving request fields, including asset/currency, amount, source, destination, and fee policy.
3. Insert/claim the key and perform the ledger transition atomically where they share a database.
4. For cross-service operations, use a durable state machine and one stable downstream idempotency key.
5. Do not acknowledge a webhook until its authenticated event identity is durably recorded.
6. Test same-key/same-body replay, same-key/different-body conflict, concurrent duplicate calls, and provider callback replay.
