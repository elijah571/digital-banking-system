package org.elibanking.accountservice.ledger;

import java.util.List;

/**
 * The result of one reconciliation pass (wallet-plan 3.2). Each issue is a human-readable line. An empty list means the
 * ledger is consistent.
 */
public record ReconciliationReport(List<String> issues) {

    public ReconciliationReport {
        issues = List.copyOf(issues);
    }

    public boolean clean() {
        return issues.isEmpty();
    }
}
