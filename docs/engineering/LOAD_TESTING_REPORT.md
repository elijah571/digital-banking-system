# Load Testing Report

## Result

No load test or throughput benchmark has been run in this implementation pass. There is no production-like endpoint, dataset, Cloudflare path, or provider sandbox configuration available to produce a representative number. Performance claims are therefore not made.

## Required run

Run in an isolated staging environment with synthetic accounts and provider simulators. Record tool/version, commit, host/container sizing, dataset, request mix, concurrency, warm-up, duration, and percentile latency. Include concurrent same-sender withdrawals/transfers, webhook duplication, slow downstream services, Redis loss, Kafka broker loss, and MySQL pool saturation.

Report p50/p95/p99, success/error rates, throughput, pool wait, deadlocks, MySQL query plans, Kafka lag/outbox backlog, Redis latency, CPU, and memory. Reconcile all ledgers before and after; any mismatch fails the run regardless of latency.
