package org.elibanking.accountservice.ledger;

/** Base class for ledger rule violations. Each subclass maps to one HTTP status in the API layer. */
public abstract class LedgerException extends RuntimeException {

    protected LedgerException(String message) {
        super(message);
    }
}
