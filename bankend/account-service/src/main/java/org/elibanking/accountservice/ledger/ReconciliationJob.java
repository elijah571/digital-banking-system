package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.audit.AuditLogWriter;
import org.elibanking.accountservice.outbox.JsonStrings;
import org.elibanking.accountservice.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Runs the reconciliation checks on a schedule (wallet-plan 3.2, hourly by default). If anything is wrong, it writes an
 * audit row and an outbox alert in one transaction, so the alert is published by the relay and cannot be lost.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliation;
    private final AuditLogWriter audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public ReconciliationJob(ReconciliationService reconciliation, AuditLogWriter audit, OutboxWriter outbox,
                             TransactionTemplate tx) {
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
    }

    @Scheduled(cron = "${ledger.reconciliation.cron:0 0 * * * *}")
    public void run() {
        reconcileAndAlert();
    }

    /** Runs one pass. Returns the report, and alerts if the report is not clean. */
    public ReconciliationReport reconcileAndAlert() {
        ReconciliationReport report = reconciliation.check();
        if (!report.clean()) {
            String correlationId = UUID.randomUUID().toString();
            String details = "{\"issues\":[" + String.join(",",
                    report.issues().stream().map(JsonStrings::quote).toList()) + "]}";
            tx.executeWithoutResult(status -> {
                audit.record("system", "LEDGER_RECONCILIATION_FAILED", "LEDGER", "journal", correlationId, details);
                outbox.append("ledger.alerts", "ledger.reconciliation.failed", "ledger", correlationId, details);
            });
            log.error("ledger reconciliation failed: {} issue(s); alert {}", report.issues().size(), correlationId);
        }
        return report;
    }
}
