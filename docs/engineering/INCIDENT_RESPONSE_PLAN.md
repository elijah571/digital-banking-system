# Incident Response Plan

## Severity and first actions

- **SEV-1 funds/security:** confirmed unauthorized movement, signing key exposure, ledger inconsistency, or active provider compromise. Page engineering, security, finance, and executive incident leads immediately.
- **SEV-2 degraded money path:** elevated failed/uncertain payouts, webhook backlog, database saturation, or Kafka lag without confirmed loss. Assign incident commander and service owner.
- Preserve logs, audit trails, provider references, request IDs, and timestamps. Do not include PINs, passwords, bearer tokens, refresh tokens, private keys, or full payment credentials in incident chat.

## Containment

Disable the affected provider/API operation through a reviewed feature/config switch, preserve read access where safe, and stop retries only after recording durable pending state. Rotate compromised credentials through the secret manager/provider console. Do not edit or delete posted journal rows; use approved reversal entries.

## Recovery and communication

Reconcile internal state against provider state before resuming external sends. Require a second operator for re-enabling high-risk financial operations. Notify affected customers through verified channels; never redirect payments based only on an email request. Record decisions and approvals in the incident log.

## Follow-up

Within the agreed post-incident window, document timeline, root cause, customer impact, ledger/provider reconciliation, control gaps, and owners/due dates. Test this plan in a tabletop exercise. On-call contacts and provider escalation details must be maintained outside source control.
