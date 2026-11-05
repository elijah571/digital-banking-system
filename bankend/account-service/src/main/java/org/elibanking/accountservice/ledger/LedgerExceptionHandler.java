package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.idempotency.IdempotencyInProgressException;
import org.elibanking.accountservice.idempotency.IdempotencyKeyMismatchException;
import org.elibanking.common.money.CurrencyMismatchException;
import org.elibanking.common.money.InvalidAmountException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps ledger rule violations on the internal API to HTTP statuses (wallet-plan 3.2). */
@RestControllerAdvice(assignableTypes = {LedgerController.class, AdminLedgerController.class, AccountStatementController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LedgerExceptionHandler {

    @ExceptionHandler(LedgerNotFoundException.class)
    ProblemDetail notFound(LedgerNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({InvalidPostingException.class, InvalidAmountException.class, CurrencyMismatchException.class})
    ProblemDetail invalid(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler({InsufficientFundsException.class, IdempotencyConflictException.class,
            IdempotencyKeyMismatchException.class})
    ProblemDetail unprocessable(RuntimeException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e);
    }

    @ExceptionHandler(LedgerAccessDeniedException.class)
    ProblemDetail forbidden(LedgerAccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    ProblemDetail inProgress(IdempotencyInProgressException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(AlreadyReversedException.class)
    ProblemDetail conflict(AlreadyReversedException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    private static ProblemDetail problem(HttpStatus status, RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }
}
