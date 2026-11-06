package org.elibanking.paymentservice.exception;

/**
 * A deposit event could not be written to the outbox or marked processed, so the rows roll back and the webhook answers 500, and
 * Stripe retries the event (wallet-plan 3.8).
 */
public class DepositNotRecordedException extends RuntimeException {
    public DepositNotRecordedException(String stripeEventId, Throwable cause) {
        super("Deposit event " + stripeEventId + " could not be recorded", cause);
    }
}
