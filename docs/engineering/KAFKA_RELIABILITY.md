# Kafka Reliability

## Existing design

Services publish durable outbox rows to Kafka and use idempotency at ledger and event boundaries. Wallet deposit events use an error handler that retries indefinitely rather than skipping a record that may represent money. This protects correctness but can block its partition if a bad record or permanent dependency failure occurs.

## Current topology gap

Compose config runs one Kafka broker with replication factor one and local PLAINTEXT listeners. This is suitable for development/CI, not broker-failure tolerance or production security. No production SASL/TLS configuration or consumer-lag dashboard is checked in.

## Production requirements

- Use a replicated managed Kafka cluster or operate at least three brokers across failure domains.
- Use TLS/SASL, least-privilege ACLs, replication factor 3, `min.insync.replicas` 2, producer `acks=all`, idempotent producers, and retention sized to recovery needs.
- Define a retry and DLT policy per consumer. For financial messages, a DLT must page an operator and support audited replay; it must never turn a failed credit into a silent skip.
- Monitor consumer lag, retry age, outbox backlog, failed publication, and DLT depth.
- Version event envelopes and keep schema compatibility checks in CI.

Do not enable automatic offset commits for money handlers. Exactly-once Kafka mode does not make a MySQL ledger transaction plus a provider API call exactly-once.
