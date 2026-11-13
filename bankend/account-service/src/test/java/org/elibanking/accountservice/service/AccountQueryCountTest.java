package org.elibanking.accountservice.service;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.accountservice.security.Caller;
import org.hibernate.SessionFactory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

/** D17: list and detail reads must not add a query per returned account. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountQueryCountTest {

    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionFactory sessionFactory;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void enableStatistics() {
        sessionFactory.getStatistics().setStatisticsEnabled(true);
    }

    @Test
    void accountListAndDetailsUseAConstantNumberOfJpaQueries() {
        String owner = "query-count-" + UUID.randomUUID();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
        String first = insertAccount(owner);
        for (int i = 1; i < 25; i++) insertAccount(owner);

        var statistics = sessionFactory.getStatistics();
        statistics.clear();
        entityManager.clear();
        assertEquals(25, accounts.getMyAccounts(new Caller(owner, "query-count@example.test", "USER")).size());
        assertEquals(1, statistics.getPrepareStatementCount(), "owner list should load accounts in one query");

        statistics.clear();
        entityManager.clear();
        assertEquals(before + 25, accounts.getAllAccounts().size());
        assertEquals(1, statistics.getPrepareStatementCount(), "admin list should load accounts in one query");

        statistics.clear();
        entityManager.clear();
        accounts.getAccount(first, new Caller(owner, "query-count@example.test", "USER"));
        assertEquals(1, statistics.getPrepareStatementCount(), "detail should use one query");
    }

    private String insertAccount(String owner) {
        String id = UUID.randomUUID().toString();
        String number = String.format("%010d", Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000_000));
        jdbc.update("""
                INSERT INTO accounts (id, account_holder_name, account_number, account_status, account_type, balance,
                                      created_at, daily_transaction_limit, email, phone, updated_at, owner_id, currency)
                VALUES (?, 'Query Count', ?, 'ACTIVE', 'SAVINGS', 0.00, ?, 100000.00, ?, ?, ?, ?, 'NGN')
                """, id, number, LocalDateTime.now(), id + "@example.test", "08" + number, LocalDateTime.now(), owner);
        return number;
    }
}
