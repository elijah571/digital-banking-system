package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corruptions here are written straight to the tables, as a test fixture. Each one is undone in a {@code finally}
 * block, because the database is shared by the other tests in this context.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReconciliationTest {

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    ReconciliationJob job;

    @Autowired
    LedgerPostingService ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aConsistentLedgerIsClean() {
        long a = wallet();
        long b = wallet();
        fund(a, "100.00");
        ledger.post(PostingRequest.of("TRANSFER", key(), List.of(
                new PostingLine(a, Side.DEBIT, Money.of("30.00", "NGN")),
                new PostingLine(b, Side.CREDIT, Money.of("30.00", "NGN")))));

        assertTrue(reconciliation.check().clean(), () -> "issues: " + reconciliation.check().issues());
    }

    @Test
    void anAccountWithoutAWalletIsReported() {
        String account = UUID.randomUUID().toString();
        String phone = "081" + String.format("%08d", Math.abs(account.hashCode()) % 100_000_000);
        jdbc.update("""
                INSERT INTO accounts (id, account_holder_name, account_number, account_status, account_type, balance,
                                      created_at, daily_transaction_limit, email, phone, updated_at)
                VALUES (?, 'No Wallet', ?, 'ACTIVE', 'SAVINGS', 0.00, NOW(6), 100000.00, ?, ?, NOW(6))
                """, account, String.format("%010d", Math.abs(account.hashCode()) % 1_000_000_000L),
                account + "@example.test", phone);
        try {
            List<String> issues = reconciliation.check().issues();
            assertTrue(issues.stream().anyMatch(i -> i.endsWith("accounts have no ledger wallet")),
                    () -> "the missing wallet must be reported: " + issues);
        } finally {
            jdbc.update("DELETE FROM accounts WHERE id = ?", account);
        }
        assertTrue(reconciliation.check().clean(), () -> "issues: " + reconciliation.check().issues());
    }

    @Test
    void aChangedCachedBalanceIsReported() {
        long wallet = wallet();
        fund(wallet, "10.00");
        jdbc.update("UPDATE ledger_account SET balance = balance + 1 WHERE id = ?", wallet);
        try {
            ReconciliationReport report = reconciliation.check();
            assertFalse(report.clean());
            assertTrue(report.issues().stream().anyMatch(i -> i.startsWith("ledger_account " + wallet + ": cached balance")),
                    () -> "issues: " + report.issues());
        } finally {
            jdbc.update("UPDATE ledger_account SET balance = balance - 1 WHERE id = ?", wallet);
        }
        assertTrue(reconciliation.check().clean());
    }

    @Test
    void aMirrorMismatchIsReported() {
        long wallet = wallet();
        String account = linkedAccount(wallet);
        fund(wallet, "10.00");
        jdbc.update("UPDATE accounts SET balance = 99.00 WHERE id = ?", account);
        try {
            ReconciliationReport report = reconciliation.check();
            assertTrue(report.issues().stream().anyMatch(i -> i.startsWith("accounts " + account + ": balance")),
                    () -> "issues: " + report.issues());
        } finally {
            jdbc.update("UPDATE accounts SET balance = 10.00 WHERE id = ?", account);
        }
        assertTrue(reconciliation.check().clean());
    }

    @Test
    void theJobWritesAnAuditRowAndAnOutboxAlertWhenTheLedgerIsWrong() {
        long wallet = wallet();
        fund(wallet, "10.00");
        long auditBefore = count("SELECT COUNT(*) FROM audit_log WHERE action = 'LEDGER_RECONCILIATION_FAILED'");
        long alertsBefore = count("SELECT COUNT(*) FROM outbox WHERE event_type = 'ledger.reconciliation.failed'");

        jdbc.update("UPDATE ledger_account SET balance = balance + 1 WHERE id = ?", wallet);
        try {
            ReconciliationReport report = job.reconcileAndAlert();

            assertFalse(report.clean());
            assertEquals(auditBefore + 1,
                    count("SELECT COUNT(*) FROM audit_log WHERE action = 'LEDGER_RECONCILIATION_FAILED'"));
            assertEquals(alertsBefore + 1,
                    count("SELECT COUNT(*) FROM outbox WHERE event_type = 'ledger.reconciliation.failed'"));
        } finally {
            jdbc.update("UPDATE ledger_account SET balance = balance - 1 WHERE id = ?", wallet);
        }
        assertTrue(reconciliation.check().clean());
    }

    // ------------------------------------------------------------------ helpers

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    private void fund(long wallet, String amount) {
        long equity = jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = 'OPENING_BALANCE_EQUITY:NGN'",
                Long.class);
        ledger.post(PostingRequest.of("FUNDING", key(), List.of(
                new PostingLine(equity, Side.DEBIT, Money.of(amount, "NGN")),
                new PostingLine(wallet, Side.CREDIT, Money.of(amount, "NGN")))));
    }

    private long wallet() {
        String ownerRef = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ledger_account (owner_type, owner_ref, type, currency) VALUES ('USER', ?, 'LIABILITY', 'NGN')",
                ownerRef);
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE owner_ref = ?", Long.class, ownerRef);
    }

    private String linkedAccount(long walletId) {
        String id = UUID.randomUUID().toString();
        String number = String.format("%010d", java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L));
        String phone = "082" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextLong(100_000_000L));
        jdbc.update("""
                INSERT INTO accounts (id, account_holder_name, account_number, account_status, account_type, balance,
                                      created_at, daily_transaction_limit, email, phone, updated_at, currency,
                                      ledger_account_id)
                VALUES (?, 'Reconcile Test', ?, 'ACTIVE', 'SAVINGS', 0.00, NOW(6), 100000.00, ?, ?, NOW(6), 'NGN', ?)
                """, id, number, id + "@example.test", phone, walletId);
        return id;
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
