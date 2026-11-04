package org.elibanking.accountservice.ledger;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared checks for ledger tests. */
final class LedgerAssertions {

    private LedgerAssertions() {
    }

    /**
     * The reconciliation identity from wallet-plan 3.2: each cached balance equals the signed sum of its lines, and
     * debits equal credits per currency across the whole journal.
     */
    static void assertReconciles(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT la.id, la.type, la.balance,
                       COALESCE(SUM(CASE WHEN jl.direction = 'DEBIT'  THEN jl.amount END), 0) AS dr,
                       COALESCE(SUM(CASE WHEN jl.direction = 'CREDIT' THEN jl.amount END), 0) AS cr
                FROM ledger_account la
                LEFT JOIN journal_line jl ON jl.ledger_account_id = la.id
                GROUP BY la.id, la.type, la.balance
                """);
        for (Map<String, Object> row : rows) {
            BigDecimal dr = (BigDecimal) row.get("dr");
            BigDecimal cr = (BigDecimal) row.get("cr");
            String type = (String) row.get("type");
            boolean debitNormal = type.equals("ASSET") || type.equals("EXPENSE");
            BigDecimal expected = debitNormal ? dr.subtract(cr) : cr.subtract(dr);
            BigDecimal stored = (BigDecimal) row.get("balance");
            assertEquals(0, stored.compareTo(expected),
                    "ledger_account " + row.get("id") + " cached balance " + stored + " != signed lines " + expected);
        }
        List<Map<String, Object>> totals = jdbc.queryForList("""
                SELECT currency,
                       SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END) AS dr,
                       SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS cr
                FROM journal_line GROUP BY currency
                """);
        for (Map<String, Object> total : totals) {
            assertEquals(0, ((BigDecimal) total.get("dr")).compareTo((BigDecimal) total.get("cr")),
                    "journal is unbalanced in " + total.get("currency"));
        }
    }
}
