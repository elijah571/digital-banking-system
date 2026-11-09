package org.elibanking.transactionservice.exception;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.elibanking.transactionservice.service.WalletTransferRejectedException;
import org.elibanking.transactionservice.service.WalletUnavailableException;
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

    @ExceptionHandler(TransactionRejectedException.class)
    public ProblemDetail handleRejected(TransactionRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    public ProblemDetail handleIdempotencyInProgress(IdempotencyInProgressException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    public ProblemDetail handleIdempotencyMismatch(IdempotencyKeyMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ProblemDetail handleInvalidOtp(InvalidOtpException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** The transfer PIN is locked: 423, and Retry-After says when to try again (wallet-plan 3.5). */
    @ExceptionHandler(PinLockedException.class)
    public ResponseEntity<ProblemDetail> pinLocked(PinLockedException e) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, e.getMessage()));
    }

    /** wallet-service did not answer the PIN check: the transfer is refused (503, fail closed). */
    @ExceptionHandler(PinUnavailableException.class)
    public ProblemDetail pinUnavailable(PinUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(WalletUnavailableException.class)
    public ProblemDetail walletUnavailable(WalletUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(WalletTransferRejectedException.class)
    public ProblemDetail walletRejected(WalletTransferRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
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
