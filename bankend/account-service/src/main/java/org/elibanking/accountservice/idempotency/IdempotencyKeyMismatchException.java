package org.elibanking.accountservice.idempotency;

/** The idempotency key was already used for a different request. HTTP 422. */
public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException(String message) {
        super(message);
    }
}
