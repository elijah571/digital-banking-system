package org.elibanking.accountservice.ledger;

/** The entry is malformed or unbalanced. Nothing is written. HTTP 400. */
public class InvalidPostingException extends LedgerException {

    public InvalidPostingException(String message) {
        super(message);
    }
}
