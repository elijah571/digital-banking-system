package org.elibanking.walletservice.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps wallet errors to problem responses. Each refusal is a status the client can act on. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PinOtpCooldownException.class)
    public ResponseEntity<ProblemDetail> cooldown(PinOtpCooldownException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }

    @ExceptionHandler(PinOtpUnavailableException.class)
    public ProblemDetail sendFailed(PinOtpUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** Redis is down or too slow: the code cannot be checked, so the request is refused (threat model T13, fail closed). */
    @ExceptionHandler({RedisConnectionFailureException.class, QueryTimeoutException.class})
    public ProblemDetail redisDown() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "PIN codes are unavailable right now. Try again shortly.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
