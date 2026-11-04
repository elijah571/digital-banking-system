package org.elibanking.accountservice.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only checks on the ledger (wallet-plan 3.2):
 * <ul>
 *   <li>each cached balance equals the signed sum of its journal lines, on the account's normal side;</li>
 *   <li>debits equal credits per currency across the whole journal;</li>
 *   <li>while the expand phase lasts, {@code accounts.balance} equals its wallet's balance.</li>
 * </ul>
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReconciliationReport check() {
        List<String> issues = new ArrayList<>();

        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT la.id, la.type, la.balance,
                       COALESCE(SUM(CASE WHEN jl.direction = 'DEBIT'  THEN jl.amount END), 0) AS dr,
                       COALESCE(SUM(CASE WHEN jl.direction = 'CREDIT' THEN jl.amount END), 0) AS cr
                FROM ledger_account la
                LEFT JOIN journal_line jl ON jl.ledger_account_id = la.id
                GROUP BY la.id, la.type, la.balance
                """);
        for (Map<String, Object> row : accounts) {
            BigDecimal dr = (BigDecimal) row.get("dr");
            BigDecimal cr = (BigDecimal) row.get("cr");
            boolean debitNormal = "ASSET".equals(row.get("type")) || "EXPENSE".equals(row.get("type"));
            BigDecimal expected = debitNormal ? dr.subtract(cr) : cr.subtract(dr);
            BigDecimal stored = (BigDecimal) row.get("balance");
            if (stored.compareTo(expected) != 0) {
                issues.add("ledger_account " + row.get("id") + ": cached balance " + stored.toPlainString()
                        + " != signed lines " + expected.toPlainString());
            }
        }

        List<Map<String, Object>> currencies = jdbc.queryForList("""
                SELECT currency,
                       SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END) AS dr,
                       SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS cr
                FROM journal_line GROUP BY currency
                """);
        for (Map<String, Object> row : currencies) {
            BigDecimal dr = (BigDecimal) row.get("dr");
            BigDecimal cr = (BigDecimal) row.get("cr");
            if (dr.compareTo(cr) != 0) {
                issues.add("journal is unbalanced in " + row.get("currency") + ": debits " + dr.toPlainString()
                        + ", credits " + cr.toPlainString());
            }
        }

        List<Map<String, Object>> mirrors = jdbc.queryForList("""
                SELECT a.id, a.balance AS account_balance, la.balance AS wallet_balance
                FROM accounts a JOIN ledger_account la ON la.id = a.ledger_account_id
                WHERE a.balance <> la.balance
                """);
        for (Map<String, Object> row : mirrors) {
            issues.add("accounts " + row.get("id") + ": balance " + row.get("account_balance")
                    + " does not match its wallet " + row.get("wallet_balance"));
        }

        // Every account has a wallet (wallet-plan 3.4). An account without one cannot take admin adjustments or ledger
        // transfers, and the checks above never see it, so this check is what keeps the report from being blind to it.
        Long withoutWallet = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE ledger_account_id IS NULL", Long.class);
        if (withoutWallet != null && withoutWallet > 0) {
            issues.add(withoutWallet + " accounts have no ledger wallet");
        }

        return new ReconciliationReport(issues);
    }
}
