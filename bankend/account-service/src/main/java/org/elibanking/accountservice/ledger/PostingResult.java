package org.elibanking.accountservice.ledger;

/**
 * @param entryId  the journal entry id
 * @param replayed {@code true} when the idempotency key had already been posted, so nothing new was written
 */
public record PostingResult(long entryId, boolean replayed) {
}
