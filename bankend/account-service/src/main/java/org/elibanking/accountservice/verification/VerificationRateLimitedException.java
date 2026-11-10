package org.elibanking.accountservice.verification;

public class VerificationRateLimitedException extends RuntimeException {
    public VerificationRateLimitedException() {
        super("Please wait before trying another verification");
    }
}
