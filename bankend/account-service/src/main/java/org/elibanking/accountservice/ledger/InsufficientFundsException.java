package org.elibanking.accountservice.ledger;

/** A customer (USER) ledger account would go below zero. Nothing is written. HTTP 422. */
public class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
