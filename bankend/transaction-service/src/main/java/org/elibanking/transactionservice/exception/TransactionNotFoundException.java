package org.elibanking.transactionservice.exception;

/** Thrown when a transaction id does not exist (HTTP 404). */
public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(String message) {
        super(message);
    }
}
