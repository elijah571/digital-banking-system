package org.elibanking.walletservice.pin;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sets or changes the transfer PIN (wallet-plan 3.5). It needs the emailed code and the owner's password, in that order. The
 * answers are: 200 when set or changed; 400 for a code that is wrong, missing or used up; 403 for a wrong password; 429 when
 * the password checks are limited; 503 when auth-service cannot answer.
 */
@RestController
@RequestMapping("/api/v1/wallet/pin")
public class PinController {

    private final PinChangeService changes;

    public PinController(PinChangeService changes) {
        this.changes = changes;
    }

    @PutMapping
    public ResponseEntity<Object> change(@Valid @RequestBody ChangePinRequest request,
                                         @AuthenticationPrincipal Jwt user) {
        String email = user.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Sign in again: this session has no email address for the PIN notice");
        }
        PinChangeService.Outcome outcome = changes.change(user.getSubject(), email, request.code(),
                request.password(), request.pin(), user.getSubject());
        return switch (outcome.result()) {
            case SET -> ResponseEntity.ok(Map.of("status", "PIN_SET"));
            case CHANGED -> ResponseEntity.ok(Map.of("status", "PIN_CHANGED"));
            case WRONG_CODE -> problem(HttpStatus.BAD_REQUEST, "Incorrect code. " + triesLeft(outcome.codeTriesLeft()));
            case NO_CODE -> problem(HttpStatus.BAD_REQUEST, "No code is active. Ask for a new one.");
            case CODE_USED -> problem(HttpStatus.BAD_REQUEST, "That code has been used up. Ask for a new one.");
            case WRONG_PASSWORD -> problem(HttpStatus.FORBIDDEN, "Wrong password.");
            case PASSWORD_RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(outcome.retryAfterSeconds()))
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                            "Too many password checks. Try again later."));
        };
    }

    private static String triesLeft(int left) {
        return left == 1 ? "1 try left." : left + " tries left.";
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
