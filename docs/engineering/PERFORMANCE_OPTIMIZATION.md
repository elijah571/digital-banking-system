# Performance Optimization

## Current baseline

- Gateway auth and user routes use Redis-backed request rate limiting.
- Wallet name enquiry and PIN/password checks have bounded throttling.
- Transfer history uses pagination/query-count tests; ledger requests lock accounts in ascending order.
- Service DB pools now have explicit Compose controls: max 10, min idle 2, 3 s connection wait, 1 s validation, and a 29 minute max lifetime. Override with `DB_POOL_*` only after measuring the MySQL connection budget.
- No production-like load benchmark has been run. Do not infer throughput from unit tests.

## Measurement plan

1. Capture p50/p95/p99 by route and downstream dependency; split gateway, service, SQL, Redis, and Kafka time.
2. Load realistic data volumes and use a fixed mix of reads, internal transfers, payouts, and webhook retries.
3. Run a concurrency case with a hot sender account separately from a distributed-account case.
4. Record pool wait, InnoDB lock wait/deadlocks, query plans, Kafka lag, Redis latency, CPU, memory, and error rate.
5. Tune one layer at a time; preserve ledger locks and idempotency. A faster balance check cannot replace the locked ledger posting.

## Scaling constraints

The current Compose Kafka broker and single service instances are development-scale defaults. Scale stateless HTTP services only after confirming shared state is in MySQL/Redis and total connection pools remain below the database budget. Put static frontend assets behind a CDN; never cache authenticated banking responses or webhook/API responses at the edge.
