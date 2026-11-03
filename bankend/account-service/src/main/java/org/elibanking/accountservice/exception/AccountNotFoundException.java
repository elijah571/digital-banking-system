package org.elibanking.accountservice.exception;

/** Thrown when an account number does not exist (HTTP 404). */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}
