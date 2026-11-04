package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.audit.AuditLogWriter;
import org.elibanking.accountservice.outbox.JsonStrings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postings made by calling services through the internal API (wallet-plan 3.2, 3.9). Each new entry writes one audit row
 * in the same transaction, so the entry and its audit row commit or roll back together. A replay writes nothing, so it
 * writes no second row. The actor is the caller's service token {@code sub}, and the correlation id is the request's
 * {@code X-Request-Id}.
 *
 * <p>Admin adjustments do not come through here. {@link AdminAdjustments} writes its own {@code ADMIN_*} row, so an admin
 * credit has exactly one audit row.
 */
@Service
public class ServicePostings {

    static final String POSTED = "LEDGER_ENTRY_POSTED";
    static final String REVERSED = "LEDGER_ENTRY_REVERSED";
    static final String ENTITY_TYPE = "JOURNAL_ENTRY";

    private final LedgerPostingService ledger;
    private final AuditLogWriter audit;

    public ServicePostings(LedgerPostingService ledger, AuditLogWriter audit) {
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional
    public PostingResult post(PostingRequest request, String actor, String requestId) {
        PostingResult result = ledger.post(request);
        if (!result.replayed()) {
            audit.record(actor, POSTED, ENTITY_TYPE, String.valueOf(result.entryId()), requestId,
                    "{\"type\":" + quote(request.type())
                            + ",\"reference\":" + quote(request.reference())
                            + ",\"createdBy\":" + quote(request.createdBy())
                            + ",\"correlationId\":" + quote(request.correlationId()) + "}");
        }
        return result;
    }

    @Transactional
    public PostingResult reverse(long entryId, String idempotencyKey, String createdBy, String actor, String requestId) {
        PostingResult result = ledger.reverse(entryId, idempotencyKey, createdBy);
        if (!result.replayed()) {
            audit.record(actor, REVERSED, ENTITY_TYPE, String.valueOf(result.entryId()), requestId,
                    "{\"reverses\":" + entryId + "}");
        }
        return result;
    }

    private static String quote(String value) {
        return value == null ? "null" : JsonStrings.quote(value);
    }
}
