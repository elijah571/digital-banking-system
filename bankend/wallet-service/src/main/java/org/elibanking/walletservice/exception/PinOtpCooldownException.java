package org.elibanking.walletservice.exception;

/** A code was asked for less than a minute ago (threat model D-P11). Mapped to 429 with Retry-After. */
public class PinOtpCooldownException extends RuntimeException {

    private final long retryAfterSeconds;

    public PinOtpCooldownException(long retryAfterSeconds) {
        super("A code was sent a moment ago. Try again shortly.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
