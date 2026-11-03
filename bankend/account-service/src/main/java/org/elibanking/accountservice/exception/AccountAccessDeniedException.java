package org.elibanking.accountservice.exception;

/** The logged-in user tried to access an account that is not theirs (HTTP 403). */
public class AccountAccessDeniedException extends RuntimeException {
    public AccountAccessDeniedException(String message) {
        super(message);
    }
}
