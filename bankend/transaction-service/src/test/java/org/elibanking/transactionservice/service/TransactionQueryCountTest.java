package org.elibanking.transactionservice.service;

import jakarta.persistence.EntityManager;
import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.security.Caller;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D17: transaction list and detail reads must not issue one query per row. */
@SpringBootTest(properties = {"transfer.pin-required=false", "outbox.relay.enabled=false", "transfer.recovery.enabled=false",
        "internal-auth.jwt-secret=transaction-query-test-internal-secret-0123456789",
        "auth.jwt-secret=transaction-query-test-user-secret-0123456789",
        "otp.hmac-key=transaction-query-test-otp-hmac-secret-0123456789"})
@Import(TestcontainersConfiguration.class)
@Transactional
class TransactionQueryCountTest {

    @Autowired TransactionalService transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionFactory sessionFactory;
    @Autowired EntityManager entityManager;

    @Test
    void transactionListsAndDetailsUseAConstantNumberOfJpaQueries() {
        String sender = "query-count-" + UUID.randomUUID();
        String owner = "query-owner-" + UUID.randomUUID();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        String id = insertTransaction(sender, owner);
        for (int i = 1; i < 25; i++) insertTransaction(sender, owner);

        var statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        entityManager.clear();
        assertEquals(25, transactions.getTransactionHistory(sender,
                new Caller("query-admin", "query-count@example.test", "ADMIN")).size());
        assertEquals(1, statistics.getPrepareStatementCount(), "account history should use one query");

        statistics.clear();
        entityManager.clear();
        assertEquals(before + 25, transactions.getAllTransactions().size());
        assertEquals(1, statistics.getPrepareStatementCount(), "admin history should use one query");

        statistics.clear();
        entityManager.clear();
        transactions.getTransaction(id, new Caller(owner, "query-count@example.test", "USER"));
        assertEquals(1, statistics.getPrepareStatementCount(), "detail should use one query");
    }

    private String insertTransaction(String sender, String owner) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO transactions (id, sender_account_number, sender_user_id, receiver_account_number,
                                          reference_number, type, status, amount, created_at, version)
                VALUES (?, ?, ?, '9000000001', ?, 'TRANSFER', 'COMPLETED', ?, ?, 0)
                """, id, sender, owner, "query-" + id, new BigDecimal("1.00"), LocalDateTime.now());
        return id;
    }
}
