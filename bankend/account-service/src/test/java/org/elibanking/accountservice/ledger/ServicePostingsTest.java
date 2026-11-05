package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A posting and its audit row are one transaction (wallet-plan 3.9): both commit, or neither does.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ServicePostingsTest {

    @Autowired
    ServicePostings postings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aPostingAndItsAuditRowCommitTogether() {
        String requestId = "req-" + UUID.randomUUID();

        PostingResult result = postings.post(systemEntry(key()), "transaction-service", requestId);

        assertFalse(result.replayed());
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'JOURNAL_ENTRY' AND entity_id = ?"
                + " AND action = 'LEDGER_ENTRY_POSTED' AND actor = 'transaction-service' AND correlation_id = ?",
                String.valueOf(result.entryId()), requestId));
    }

    @Test
    void aPostingAndItsAuditRowRollBackTogetherWhenTheCallerFailsAfterwards() {
        String key = key();
        String requestId = "req-" + UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            postings.post(systemEntry(key), "transaction-service", requestId);
            throw new IllegalStateException("the caller fails after the posting");
        }));

        assertEquals(0L, count("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?", key));
        assertEquals(0L, count("SELECT COUNT(*) FROM audit_log WHERE correlation_id = ?", requestId));
    }

    @Test
    void aFailingAuditWriteTakesThePostingWithIt() {
        String key = key();
        // audit_log.actor is VARCHAR(100). This insert fails after the entry and its lines are already written.
        String tooLongActor = "a".repeat(101);

        assertThrows(DataAccessException.class,
                () -> postings.post(systemEntry(key), tooLongActor, "req-" + UUID.randomUUID()));

        assertEquals(0L, count("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?", key));
        assertEquals(0L, count("SELECT COUNT(*) FROM journal_line jl JOIN journal_entry je ON je.id = jl.entry_id"
                + " WHERE je.idempotency_key = ?", key));
    }

    // ------------------------------------------------------------------ helpers

    private static String key() {
        return "svc-postings-" + UUID.randomUUID();
    }

    /** A balanced system-to-system entry, so the test needs no funded wallet. */
    private PostingRequest systemEntry(String key) {
        return PostingRequest.of("TEST", key, List.of(
                new PostingLine(systemAccount("PAYOUT_PAYABLE:NGN"), Side.DEBIT, Money.of("1.00", "NGN")),
                new PostingLine(systemAccount("PROVIDER_CLEARING:STRIPE:NGN"), Side.CREDIT, Money.of("1.00", "NGN"))));
    }

    private long systemAccount(String code) {
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class, code);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? -1 : n;
    }
}
