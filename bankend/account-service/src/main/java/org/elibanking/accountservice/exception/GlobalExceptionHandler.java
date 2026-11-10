package org.elibanking.accountservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.elibanking.accountservice.verification.DojahProviderUnavailableException;
import org.elibanking.accountservice.verification.VerificationRateLimitedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps business exceptions to proper HTTP status codes (RFC 9457 problem+json bodies). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidOpeningDepositException.class)
    public ProblemDetail handleBadOpeningDeposit(InvalidOpeningDepositException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleNotFound(AccountNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AccountAccessDeniedException.class)
    public ProblemDetail handleForbidden(AccountAccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ProblemDetail handleConflict(AccountAlreadyExistsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(AccountOperationException.class)
    public ProblemDetail handleRuleViolation(AccountOperationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(DojahProviderUnavailableException.class)
    public ProblemDetail handleProviderUnavailable(DojahProviderUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(VerificationRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(VerificationRateLimitedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(problem);
    }
}
