package org.elibanking.transactionservice.exception;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Maps business exceptions to proper HTTP status codes (RFC 9457 problem+json bodies). */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleNotFound(TransactionNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TransactionAccessDeniedException.class)
    public ProblemDetail handleForbidden(TransactionAccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** e.g. verifying an OTP for a transaction that is not awaiting verification. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleInvalidState(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Errors returned by account-service (via Feign). 4xx responses such as "Insufficient funds" (422)
     * or "Account not found" (404) are passed through to the client with the original message;
     * anything else means account-service is unhealthy or unreachable.
     */
    @ExceptionHandler(FeignException.class)
    public ProblemDetail handleAccountServiceError(FeignException e) {
        if (e.status() >= 400 && e.status() < 500) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), downstreamDetail(e));
        }
        log.error("account-service call failed: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Account service unavailable, please retry");
    }

    private String downstreamDetail(FeignException e) {
        try {
            JsonNode body = objectMapper.readTree(e.contentUTF8());
            if (body != null && body.hasNonNull("detail")) {
                return body.get("detail").asString();
            }
        } catch (Exception ignored) {
            // not a problem+json body — fall through
        }
        return "Account service rejected the request";
    }
}
