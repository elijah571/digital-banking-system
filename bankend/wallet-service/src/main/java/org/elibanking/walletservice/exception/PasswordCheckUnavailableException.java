package org.elibanking.walletservice.exception;

/** auth-service could not be reached, or did not answer: the password is not checked, so the change is refused (503). */
public class PasswordCheckUnavailableException extends RuntimeException {

    public PasswordCheckUnavailableException() {
        super("The password check is unavailable right now. Try again shortly.");
    }
}
