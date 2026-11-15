# System Architecture

The canonical architecture diagram and service catalog live in [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md). This note records security and operations boundaries that the diagrams do not show.

## Runtime boundaries

- Spring Boot services are behind Spring Cloud Gateway. User JWTs are validated at the gateway and again by each service that serves authenticated user routes.
- MySQL/InnoDB is authoritative. Each service owns its schema and Flyway migrations; account-service alone posts NGN and crypto subledger entries.
- Kafka carries asynchronous signals. Outbox rows make publication retryable; message delivery remains at-least-once, so handlers and ledger postings use durable idempotency.
- Redis holds bounded/rate-limit state; it is not a balance or transaction source of truth.
- payment-service verifies provider-specific payment callbacks. crypto-service verifies Fireblocks Webhook V2 and fetches provider transaction state before posting.

## Production boundary

Compose production publishes the gateway only. The checked-in Compose Kafka topology is a single local broker and is not a multi-node HA cluster. Production HA requires a managed Kafka cluster or an independently operated replicated cluster, TLS/SASL, and topic replication appropriate to the deployment. Production DNS, Cloudflare, secrets storage, backup object lock, and provider accounts are external operator responsibilities; repository config alone does not activate them.

## Status

For implementation details and known gaps, see [Banking Security Architecture](BANKING_SECURITY_ARCHITECTURE.md), [Kafka Reliability](KAFKA_RELIABILITY.md), and [Disaster Recovery](DISASTER_RECOVERY.md).
