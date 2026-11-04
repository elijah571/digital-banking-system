package org.elibanking.transactionservice.exception;

/**
 * A wrong OTP that is not the last allowed attempt (wallet-plan D6). The transfer stays PENDING_VERIFICATION. The third wrong
 * attempt does not throw this: it flags the transfer, refunds the sender and blocks the account.
 */
public class InvalidOtpException extends RuntimeException {

    public InvalidOtpException(long attemptsLeft) {
        super("Incorrect OTP. " + (attemptsLeft == 1 ? "1 attempt left" : attemptsLeft + " attempts left"));
    }
}
