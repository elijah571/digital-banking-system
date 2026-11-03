package org.elibanking.transactionservice.exception;

/** The logged-in user tried to use or view something that is not theirs (HTTP 403). */
public class TransactionAccessDeniedException extends RuntimeException {
    public TransactionAccessDeniedException(String message) {
        super(message);
    }
}
