package org.elibanking.transactionservice.exception;

/** The same Idempotency-Key was used before for a different request (wallet-plan 3.6). Maps to 422. */
public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException(String message) {
        super(message);
    }
}
