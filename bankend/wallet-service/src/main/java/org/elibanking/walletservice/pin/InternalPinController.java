package org.elibanking.walletservice.pin;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The transfer PIN check, called by transaction-service only (wallet-plan 3.5, D2, D-P8). A right PIN is 200. A wrong PIN and
 * a user with no PIN are 403, with different details. A locked PIN is 423 with Retry-After. Every answer is audited.
 */
@RestController
@RequestMapping("/internal/v1/pin")
public class InternalPinController {

    private final PinService pins;

    public InternalPinController(PinService pins) {
        this.pins = pins;
    }

    @PostMapping("/verify")
    public ResponseEntity<Object> verify(@Valid @RequestBody VerifyPinRequest request,
                                         @AuthenticationPrincipal Jwt caller) {
        PinService.Check check = pins.check(request.userId(), request.pin(), caller.getSubject());
        return switch (check.outcome()) {
            case VALID -> ResponseEntity.ok(Map.of("valid", true));
            case WRONG -> problem(HttpStatus.FORBIDDEN, "Wrong PIN");
            case NOT_SET -> problem(HttpStatus.FORBIDDEN, "Set a PIN first");
            case LOCKED -> ResponseEntity.status(HttpStatus.LOCKED)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(check.retryAfterSeconds()))
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, "PIN is locked. Try again later."));
        };
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
