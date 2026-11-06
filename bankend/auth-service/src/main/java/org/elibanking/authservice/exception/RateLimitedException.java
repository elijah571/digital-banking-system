package org.elibanking.authservice.exception;

/** Too many password checks for one user in the window (threat model D-P10). Mapped to 429 with Retry-After. */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Too many password checks. Try again later.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
