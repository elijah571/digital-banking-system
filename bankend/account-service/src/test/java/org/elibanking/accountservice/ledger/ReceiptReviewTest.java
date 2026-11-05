package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A receipt in the wrong currency is kept in full and alerted, and nothing is posted (wallet-plan 8.19, decision 3).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReceiptReviewTest {

    @Autowired
    ReceiptReview receipts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aMismatchedReceiptIsKeptInFullAndAlertedOnce() {
        String reference = "ch_" + UUID.randomUUID();
        String payload = "{\"id\":\"" + reference + "\",\"currency\":\"usd\",\"amount\":\"10.00\"}";
        long entriesBefore = count("SELECT COUNT(*) FROM journal_entry");

        assertTrue(receipts.quarantine("STRIPE", reference, "dep-1", "CURRENCY_MISMATCH", "USD", "10.00", payload));

        assertEquals(1L, count("SELECT COUNT(*) FROM receipt_review WHERE provider_reference = '" + reference + "'"));
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM receipt_review WHERE provider_reference = ?", String.class, reference));
        assertTrue(jdbc.queryForObject("SELECT JSON_EXTRACT(payload, '$.currency') FROM receipt_review"
                        + " WHERE provider_reference = ?", String.class, reference).contains("usd"),
                "the whole receipt is kept");
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox WHERE event_type = 'ledger.receipt.quarantined'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.providerReference')) = '" + reference + "'"));
        assertEquals(entriesBefore, count("SELECT COUNT(*) FROM journal_entry"), "nothing is posted to the ledger");
    }

    @Test
    void theSameReceiptIsRecordedOnceAndAlertedOnce() {
        String reference = "ch_" + UUID.randomUUID();
        String payload = "{\"id\":\"" + reference + "\"}";
        receipts.quarantine("STRIPE", reference, null, "CURRENCY_MISMATCH", "USD", "10.00", payload);

        assertFalse(receipts.quarantine("STRIPE", reference, null, "CURRENCY_MISMATCH", "USD", "10.00", payload));

        assertEquals(1L, count("SELECT COUNT(*) FROM receipt_review WHERE provider_reference = '" + reference + "'"));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox WHERE event_type = 'ledger.receipt.quarantined'"
                + " AND aggregate_id = 'receipt:STRIPE:" + reference + "'"));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
