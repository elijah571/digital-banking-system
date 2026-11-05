package org.elibanking.transactionservice.exception;

/** A transfer that the account rules refuse before any money moves, such as a blocked account. Mapped to 422. */
public class TransactionRejectedException extends RuntimeException {

    public TransactionRejectedException(String message) {
        super(message);
    }
}
