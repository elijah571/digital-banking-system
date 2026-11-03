package org.elibanking.authservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.elibanking.authservice.dto.*;
import org.elibanking.authservice.exception.AuthException;
import org.elibanking.authservice.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register, verify email, login, refresh, logout, password reset")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "Register a new user",
            description = "Creates a USER account and emails a verification link (valid 24 h). Login is refused until the email is verified.")
    @ApiResponse(responseCode = "201", description = "Registered — check your inbox")
    @ApiResponse(responseCode = "400", description = "Validation failed (weak password, invalid email…)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Email already registered",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @GetMapping(value = "/verify-email", produces = MediaType.TEXT_HTML_VALUE)
    @SecurityRequirements
    @Operation(summary = "Verify email (link from the verification email)",
            description = "Opened in the browser from the email. Returns a small HTML page.")
    @ApiResponse(responseCode = "200", description = "Email verified")
    @ApiResponse(responseCode = "400", description = "Link invalid, already used or expired")
    public ResponseEntity<String> verifyEmail(@Parameter(description = "Token from the email link") @RequestParam String token) {
        try {
            authService.verifyEmail(token);
            return ResponseEntity.ok(page("Email verified", "Your email has been verified. You can now log in."));
        } catch (AuthException e) {
            return ResponseEntity.badRequest().body(page("Verification failed",
                    "This link is invalid, already used or expired. Request a new one from the app."));
        }
    }

    @PostMapping("/resend-verification")
    @SecurityRequirements
    @Operation(summary = "Resend the verification email",
            description = "Always returns 200 with the same message, so it cannot be used to discover registered emails.")
    public MessageResponse resendVerification(@Valid @RequestBody EmailRequest request) {
        authService.resendVerification(request.email());
        return new MessageResponse(AuthService.RESEND_MESSAGE);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in",
            description = "Returns a JWT access token (send it as `Authorization: Bearer <token>`) and a refresh token.")
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "401", description = "Wrong email or password",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Email not verified, or account disabled",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Get a new access token",
            description = "Exchanges a refresh token for a new access + refresh token pair. Each refresh token works once.")
    @ApiResponse(responseCode = "200", description = "New tokens")
    @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired or already used",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(summary = "Log out", description = "Revokes the refresh token. The access token simply expires (15 min).")
    public MessageResponse logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return new MessageResponse("Logged out");
    }

    @PostMapping("/forgot-password")
    @SecurityRequirements
    @Operation(summary = "Request a password reset email",
            description = "Emails a reset token (valid 30 min). Always returns 200 with the same message.")
    public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest request) {
        authService.forgotPassword(request.email());
        return new MessageResponse(AuthService.FORGOT_MESSAGE);
    }

    @PostMapping("/reset-password")
    @SecurityRequirements
    @Operation(summary = "Set a new password with the emailed token",
            description = "Also logs the user out everywhere (all refresh tokens are revoked).")
    @ApiResponse(responseCode = "200", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "Token invalid/expired, or weak password",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return new MessageResponse("Password has been reset. You can now log in.");
    }

    @GetMapping("/me")
    @Operation(summary = "Current user profile")
    @ApiResponse(responseCode = "200", description = "The logged-in user")
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    public UserResponse me(@Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return authService.me(userId);
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change password (logged in)", description = "Requires the current password. Logs out all other sessions.")
    @ApiResponse(responseCode = "200", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "Current password wrong, or new password too weak",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public MessageResponse changePassword(@Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String userId,
                                          @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(userId, request);
        return new MessageResponse("Password changed");
    }

    private static String page(String title, String message) {
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>%s</title>
                <style>body{font-family:sans-serif;max-width:480px;margin:80px auto;text-align:center;color:#222}</style>
                </head><body><h2>%s</h2><p>%s</p></body></html>"""
                .formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(message));
    }
}
