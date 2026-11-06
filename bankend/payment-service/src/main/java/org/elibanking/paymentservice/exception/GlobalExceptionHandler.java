package org.elibanking.paymentservice.exception;

import com.stripe.exception.StripeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;

/** Maps exceptions to proper HTTP status codes (RFC 9457 problem+json bodies). */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Invalid Stripe webhook signature, or an amount that cannot be converted to cents. */
    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    public ProblemDetail handleBadRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PaymentAccessDeniedException.class)
    public ProblemDetail handleForbidden(PaymentAccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ProblemDetail handleAccountNotFound(HttpClientErrorException.NotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Account not found");
    }

    /** Stripe rejected the call or is unreachable. */
    @ExceptionHandler(StripeException.class)
    public ProblemDetail handleStripe(StripeException e) {
        log.error("Stripe error: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Payment provider error: " + e.getUserMessage());
    }

    /** A deposit event was not recorded. Answer 500 here, so Stripe retries it; the container's error page would answer 401. */
    @ExceptionHandler(DepositNotRecordedException.class)
    public ProblemDetail handleDepositNotRecorded(DepositNotRecordedException e) {
        log.error(e.getMessage(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Deposit event not recorded; Stripe will retry");
    }
}
