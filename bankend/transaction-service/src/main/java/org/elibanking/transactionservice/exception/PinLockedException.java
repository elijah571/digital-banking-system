package org.elibanking.transactionservice.exception;

/** The transfer PIN is locked for this user (wallet-plan 3.5). Mapped to 423 with Retry-After. */
public class PinLockedException extends RuntimeException {

    private final long retryAfterSeconds;

    public PinLockedException(long retryAfterSeconds) {
        super("PIN is locked. Try again later.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
