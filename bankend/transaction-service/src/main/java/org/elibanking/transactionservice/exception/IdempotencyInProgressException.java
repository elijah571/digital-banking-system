package org.elibanking.transactionservice.exception;

/** The same Idempotency-Key is still being processed by another attempt (wallet-plan 3.6). Maps to 409. */
public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String message) {
        super(message);
    }
}
