package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.outbox.OutboxWriter;
import org.elibanking.accountservice.outbox.JsonStrings;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a receipt the ledger will not credit (wallet-plan 8.19, decision 3). Nothing is posted. The full receipt goes to
 * {@code receipt_review} and an alert goes to the outbox, in one transaction. A repeat of the same receipt is recorded once.
 */
@Service
public class ReceiptReview {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public ReceiptReview(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /**
     * @param payloadJson the whole receipt, as a JSON object
     * @return true if the review row was new, false if this receipt had already been recorded
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean quarantine(String source, String providerReference, String depositId, String reason,
                              String currency, String amount, String payloadJson) {
        try {
            jdbc.update("INSERT INTO receipt_review (source, provider_reference, deposit_id, reason, currency, amount, payload)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)", source, providerReference, depositId, reason, currency,
                    amount == null ? null : new java.math.BigDecimal(amount), payloadJson);
        } catch (DuplicateKeyException alreadyRecorded) {
            return false;
        }
        outbox.append("ledger.alerts", "ledger.receipt.quarantined", "receipt:" + source + ":" + providerReference,
                null, "{\"source\":" + JsonStrings.quote(source) + ",\"providerReference\":"
                        + JsonStrings.quote(providerReference) + ",\"reason\":" + JsonStrings.quote(reason) + "}");
        return true;
    }
}
