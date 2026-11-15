# Disaster Recovery

## Targets

Initial proposed targets (not yet measured): RPO ≤ 5 minutes using binary-log/PITR backups; RTO ≤ 2 hours to restore banking APIs in a single region. Business owners must approve these targets against provider and regulatory requirements.

## Backup architecture

Use encrypted full backups plus continuous MySQL binary logs, copied to a separate account/project with immutable object retention and deletion protection. Application DB credentials must not have permission to delete backup objects or alter retention. Store encryption keys separately and test key recovery. Back up all service schemas, Flyway history, configuration references (never secret values), and Kafka topic/config metadata.

## Restore runbook

1. Declare incident, freeze risky writes, record the recovery point, and preserve logs/audit records.
2. Provision isolated MySQL at a known version; restore the latest full backup and replay binlogs to the approved point.
3. Start services with external payouts disabled and provider webhooks held/reconciled.
4. Verify Flyway state, row counts, and schema compatibility.
5. Reconcile every NGN journal entry (debits equal credits by currency and entry), cached account balances against ledger-derived balances, and crypto journal entries by asset/base unit.
6. Reconcile pending payout/deposit/crypto states with each provider before enabling outbound writes.
7. Obtain independent finance/security approval, then restore traffic gradually and monitor.

The repository currently has no encrypted backup automation, immutable storage configuration, or completed restore exercise. Do not call backups production-ready until a dated restore report records duration, RPO, RTO, and reconciliation results.
