package org.elibanking.accountservice.idempotency;

/** Another attempt with the same key still holds a live lease. HTTP 409; the client retries later. */
public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String message) {
        super(message);
    }
}
