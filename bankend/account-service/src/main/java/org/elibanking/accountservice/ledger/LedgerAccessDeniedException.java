package org.elibanking.accountservice.ledger;

/** The caller may not read this account's statement. HTTP 403. */
public class LedgerAccessDeniedException extends LedgerException {

    public LedgerAccessDeniedException(String message) {
        super(message);
    }
}
