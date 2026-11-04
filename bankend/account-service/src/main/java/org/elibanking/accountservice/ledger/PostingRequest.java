package org.elibanking.accountservice.ledger;

import java.util.List;

/**
 * A journal entry to post. {@code idempotencyKey} is the deterministic key from wallet-plan 3.4 (for example
 * {@code tx-{id}-settle}). Posting the same key twice returns the original entry; with different content it is a
 * conflict.
 *
 * @param reversesEntryId the entry this one reverses, or {@code null}
 */
public record PostingRequest(
        String type,
        String idempotencyKey,
        String reference,
        String correlationId,
        String createdBy,
        List<PostingLine> lines,
        Long reversesEntryId) {

    public PostingRequest {
        lines = List.copyOf(lines);
    }

    public static PostingRequest of(String type, String idempotencyKey, List<PostingLine> lines) {
        return new PostingRequest(type, idempotencyKey, null, null, null, lines, null);
    }
}
