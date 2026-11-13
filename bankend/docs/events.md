# Domain events

This page records the event contract used by the services and the phase 7 target. The transactional outbox makes a database change and its event durable together. Relays send events at least once; state-changing consumers must treat a repeated `eventId` as the same event.

## Standard envelope

New and migrated events should use this JSON shape. Legacy event payloads remain supported while their producers are migrated:

```json
{
  "eventId": "<uuid>",
  "type": "<topic or domain event name>",
  "version": 1,
  "occurredAt": "<UTC timestamp>",
  "correlationId": "<X-Request-Id, when available>",
  "payload": {}
}
```

`correlationId` may be absent on existing events; phase 7 will make it consistent. The Kafka record key is the aggregate identifier. Keep event payloads additive and version them when a breaking change is needed. Never put passwords, PINs, access tokens, or transfer OTP values on general domain topics. Transfer OTP delivery uses the short-retention `notification.otp` topic.

## Current topics

| Topic | Publisher | Consumer(s) | Purpose |
| --- | --- | --- | --- |
| `transaction.initiated` | transaction-service | fraud-detection-service | Score a held transfer. |
| `transaction.verification.required` | fraud-detection-service | transaction-service | Move a transfer to OTP verification. |
| `fraud.check.clean` | fraud-detection-service | transaction-service | Continue a transfer after a clean check. |
| `fraud.detected` | transaction-service | account-service, notification-service | Block/report a fraudulent transfer. |
| `notification.otp` | transaction-service | notification-service | Deliver a transfer verification code; retained for one hour. |
| `transaction.otp.generated` | legacy | notification-service | Compatibility consumer for one release; do not publish new codes here. |
| `transaction.completed` | transaction-service | notification-service | Notify about a completed internal transfer. |
| `transaction.refunded` | transaction-service | notification-service | Notify about a refunded transfer. |
| `auth.email-verification.requested` | auth-service | notification-service | Send an email verification link. |
| `auth.password-reset.requested` | auth-service | notification-service | Send a password reset link and token. |
| `auth.password.changed` | auth-service | notification-service | Notify about a password change. |
| `wallet.pin.changed` | wallet-service | notification-service | Notify about a PIN change. |
| `payment.intent.succeeded`, `payment.intent.failed` | payment-service | wallet-service | Settle or fail a card deposit. |
| `payment.payout.success`, `payment.payout.failed`, `payment.payout.reversed` | payment-service | wallet-service | Trigger a provider status check for a payout. |
| `payment.completed`, `payment.failed` | payment-service | notification-service | Legacy payment-order notices; retained while the deprecated payment-order flow remains. |
| `wallet.deposit.completed`, `wallet.deposit.failed` | wallet-service | — | Record deposit state changes for downstream consumers. |
| `wallet.payout.<state>` | wallet-service | — | Record outbound payout state changes. |
| `ledger.alerts` | account-service | operations | Reconciliation and quarantined receipt alerts. |

## Delivery and correlation

- Outbox rows are committed with the business transaction. A relay marks a row sent only after Kafka acknowledges it.
- Delivery is at least once. State-changing consumers deduplicate by `(consumer, eventId)` in the same transaction as their effect.
- Use the event key to preserve ordering for one aggregate. Consumers must not assume ordering between different aggregates.
- `X-Request-Id` is generated or validated at the gateway, checked by servlet services, put in the request MDC, and forwarded on synchronous calls. Outbox relays and direct Kafka publishers put `eventId` and a present `correlationId` into Kafka headers. Consumers restore that correlation while handling an event, so any event they produce carries the originating request id.
- `payment.payout.*` messages are triggers, not proof of settlement. wallet-service fetches and validates provider status before it posts money to the ledger.

## Compatibility

Some records predate the shared envelope and still carry their original flat payload shape. Consumers accept both that legacy form and the shared envelope until the compatibility window closes. Update this table when adding or retiring a topic.
