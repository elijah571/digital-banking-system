package org.elibanking.accountservice.exception;

/** Thrown when a balance operation breaks a business rule, e.g. insufficient funds or inactive account (HTTP 422). */
public class AccountOperationException extends RuntimeException {
    public AccountOperationException(String message) {
        super(message);
    }
}
