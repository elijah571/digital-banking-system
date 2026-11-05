package org.elibanking.authservice.controller;

import jakarta.validation.Valid;
import org.elibanking.authservice.dto.VerifyPasswordRequest;
import org.elibanking.authservice.dto.VerifyPasswordResponse;
import org.elibanking.authservice.service.PasswordCheckService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API for the other services (wallet-plan 3.7). Only the allow-listed callers reach it, see SecurityConfig. */
@RestController
@RequestMapping("/internal/v1/auth")
public class InternalAuthController {

    private final PasswordCheckService passwordChecks;

    public InternalAuthController(PasswordCheckService passwordChecks) {
        this.passwordChecks = passwordChecks;
    }

    /**
     * Checks a user's password for wallet-service, which needs it to set or change the PIN (wallet-plan 3.5). The caller's
     * subject is the audit actor. Every call counts toward the limit, and a call over the limit is refused without a check.
     */
    @PostMapping("/verify-password")
    public VerifyPasswordResponse verifyPassword(@Valid @RequestBody VerifyPasswordRequest request,
                                                 @AuthenticationPrincipal Jwt caller) {
        return new VerifyPasswordResponse(
                passwordChecks.verify(request.userId(), request.password(), caller.getSubject()));
    }
}
