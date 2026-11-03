package org.elibanking.paymentservice.exception;

/** The logged-in user tried to pay into an account that is not theirs (HTTP 403). */
public class PaymentAccessDeniedException extends RuntimeException {
    public PaymentAccessDeniedException(String message) {
        super(message);
    }
}
