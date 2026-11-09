package org.elibanking.paymentservice.exception;

/** A verified provider event was not committed to the outbox; return 500 so the provider can retry delivery. */
public class WebhookNotRecordedException extends RuntimeException {
    public WebhookNotRecordedException(String eventId, Throwable cause) {
        super("Provider webhook event " + eventId + " could not be recorded", cause);
    }
}
