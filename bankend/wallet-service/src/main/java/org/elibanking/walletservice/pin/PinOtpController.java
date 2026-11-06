package org.elibanking.walletservice.pin;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Asks for a PIN code: it goes to the user's email (wallet-plan 3.5). Users only; see SecurityConfig. */
@RestController
@RequestMapping("/api/v1/wallet/pin")
public class PinOtpController {

    private final PinOtpService codes;

    public PinOtpController(PinOtpService codes) {
        this.codes = codes;
    }

    @PostMapping("/otp")
    public ResponseEntity<Map<String, Object>> requestCode(@AuthenticationPrincipal Jwt user) {
        String email = user.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Sign in again: this session has no email address to send the code to");
        }
        codes.issue(user.getSubject(), email, user.getSubject());
        return ResponseEntity.ok(Map.of("sent", true, "expiresInSeconds", PinOtpService.TTL.toSeconds()));
    }
}
