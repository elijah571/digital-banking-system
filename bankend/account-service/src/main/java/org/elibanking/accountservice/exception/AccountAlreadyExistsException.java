package org.elibanking.accountservice.exception;

/** Thrown when creating an account for an email that is already registered (HTTP 409). */
public class AccountAlreadyExistsException extends RuntimeException {
    public AccountAlreadyExistsException(String message) {
        super(message);
    }
}
