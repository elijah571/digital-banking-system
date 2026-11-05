package org.elibanking.accountservice.ledger;

/** The entry already has a reversal. An entry can be reversed once. HTTP 409. */
public class AlreadyReversedException extends LedgerException {

    public AlreadyReversedException(String message) {
        super(message);
    }
}
