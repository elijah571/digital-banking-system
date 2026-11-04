package org.elibanking.accountservice.ledger;

/** A ledger account or journal entry does not exist. HTTP 404. */
public class LedgerNotFoundException extends LedgerException {

    public LedgerNotFoundException(String message) {
        super(message);
    }
}
