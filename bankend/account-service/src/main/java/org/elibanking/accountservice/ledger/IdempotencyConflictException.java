package org.elibanking.accountservice.ledger;

/** The idempotency key was already used for a different entry. Nothing is written. HTTP 422. */
public class IdempotencyConflictException extends LedgerException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
