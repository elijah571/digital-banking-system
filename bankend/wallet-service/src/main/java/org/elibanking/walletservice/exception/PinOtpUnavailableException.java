package org.elibanking.walletservice.exception;

/** The code could not be sent, so the request is refused (503). The cooldown is cleared so the user can ask again. */
public class PinOtpUnavailableException extends RuntimeException {

    public PinOtpUnavailableException() {
        super("The code could not be sent right now. Try again.");
    }
}
