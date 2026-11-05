package org.elibanking.accountservice.outbox;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.accountservice.audit.AuditLogWriter;
import org.elibanking.accountservice.ledger.LedgerPostingService;
import org.elibanking.accountservice.ledger.PostingLine;
import org.elibanking.accountservice.ledger.PostingRequest;
import org.elibanking.accountservice.ledger.Side;
import org.elibanking.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxAndAuditTest {

    @Autowired
    AuditLogWriter audit;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    LedgerPostingService ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void auditRowsCannotBeChangedOrDeleted() {
        String entity = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> audit.record("tester", "TEST", "ACCOUNT", entity, null, "{\"n\":1}"));

        DataAccessException update = assertThrows(DataAccessException.class, () ->
                jdbc.update("UPDATE audit_log SET action = 'TAMPERED' WHERE entity_id = ?", entity));
        assertTrue(update.getMostSpecificCause().getMessage().contains("append-only"));

        DataAccessException delete = assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM audit_log WHERE entity_id = ?", entity));
        assertTrue(delete.getMostSpecificCause().getMessage().contains("append-only"));
    }

    @Test
    void theEnvelopeIsWrittenWithTheBusinessTransaction() {
        String aggregate = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            postSystemEntry("outbox-commit-" + aggregate);
            outbox.append("wallet.test", "TEST_EVENT", aggregate, "corr-\"1\"", "{\"amount\":\"10.00\"}");
        });

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT topic, event_type, sent_at,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.type')) AS type,
                       JSON_EXTRACT(payload, '$.version') AS version,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.correlationId')) AS correlation,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.amount')) AS amount,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.eventId')) AS event_id,
                       event_id AS row_event_id
                FROM outbox WHERE aggregate_id = ?
                """, aggregate);
        assertEquals("wallet.test", row.get("topic"));
        assertEquals("TEST_EVENT", row.get("event_type"));
        assertEquals("TEST_EVENT", row.get("type"));
        assertEquals("1", row.get("version").toString());
        assertEquals("corr-\"1\"", row.get("correlation"));
        assertEquals("10.00", row.get("amount"));
        assertEquals(row.get("row_event_id"), row.get("event_id"), "the envelope carries the row's event id");
        assertNull(row.get("sent_at"), "a new event is unsent until the relay publishes it");
    }

    @Test
    void aRolledBackTransactionLeavesNeitherTheEventNorTheLedgerEntry() {
        String aggregate = UUID.randomUUID().toString();
        String key = "outbox-rollback-" + aggregate;

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            postSystemEntry(key);
            outbox.append("wallet.test", "TEST_EVENT", aggregate, null, "{}");
            throw new IllegalStateException("boom");
        }));

        assertEquals(0L, count("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?", aggregate));
        assertEquals(0L, count("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?", key));
    }

    @Test
    void outboxAndAuditRefuseToRunOutsideATransaction() {
        String entity = UUID.randomUUID().toString();
        assertThrows(IllegalTransactionStateException.class,
                () -> outbox.append("wallet.test", "TEST_EVENT", entity, null, "{}"));
        assertThrows(IllegalTransactionStateException.class,
                () -> audit.record("tester", "TEST", "ACCOUNT", entity, null, null));
        assertEquals(0L, count("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?", entity));
        assertEquals(0L, count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", entity));
    }

    // ------------------------------------------------------------------ helpers

    /** A balanced system-to-system entry, so the test needs no funded wallet. */
    private void postSystemEntry(String key) {
        ledger.post(PostingRequest.of("TEST", key, java.util.List.of(
                new PostingLine(systemAccount("PAYOUT_PAYABLE:NGN"), Side.DEBIT, Money.of("1.00", "NGN")),
                new PostingLine(systemAccount("PROVIDER_CLEARING:STRIPE:NGN"), Side.CREDIT, Money.of("1.00", "NGN")))));
    }

    private long systemAccount(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class, code);
        assertNotNull(id);
        return id;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
