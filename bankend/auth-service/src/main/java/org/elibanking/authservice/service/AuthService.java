package org.elibanking.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.authservice.config.AuthProperties;
import org.elibanking.authservice.dto.*;
import org.elibanking.authservice.entity.AuthToken;
import org.elibanking.authservice.entity.TokenType;
import org.elibanking.authservice.entity.User;
import org.elibanking.authservice.exception.AuthException;
import org.elibanking.authservice.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Generic answers so the API never reveals whether an email is registered. */
    public static final String RESEND_MESSAGE = "If the email is registered and not yet verified, a new verification link has been sent.";
    public static final String FORGOT_MESSAGE = "If the email is registered, a password reset link has been sent.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OpaqueTokenService tokenService;
    private final JwtService jwtService;
    private final AuthEventPublisher events;
    private final AuthProperties props;

    /** BCrypt hash of a random value, compared against when the email is unknown so login timing does not leak it. */
    private String dummyHash;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new AuthException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        sendVerification(user);
        log.info("Registered user {}", user.getId());
        return UserResponse.from(user);
    }

    @Transactional
    public void verifyEmail(String token) {
        User user = tokenService.consume(token, TokenType.EMAIL_VERIFICATION);
        user.setEmailVerified(true);
        log.info("Email verified for user {}", user.getId());
    }

    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmailIgnoreCase(normalize(email))
                .filter(u -> !u.isEmailVerified() && u.isEnabled())
                .ifPresent(user -> {
                    tokenService.revokeAll(user, TokenType.EMAIL_VERIFICATION);
                    sendVerification(user);
                });
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(normalize(request.email()));
        String hash = found.map(User::getPasswordHash).orElseGet(this::dummyHash);
        if (!passwordEncoder.matches(request.password(), hash) || found.isEmpty()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        User user = found.get();
        if (!user.isEnabled()) {
            throw new AuthException(HttpStatus.FORBIDDEN, "This account has been disabled. Contact support.");
        }
        if (!user.isEmailVerified()) {
            throw new AuthException(HttpStatus.FORBIDDEN,
                    "Email not verified. Click the link we emailed you, or request a new one via /api/v1/auth/resend-verification");
        }
        log.info("User {} logged in", user.getId());
        return issueTokens(user);
    }

    /** Rotates the refresh token. Re-using an already used token revokes every session of that user (likely theft). */
    @Transactional(noRollbackFor = AuthException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        AuthToken token = tokenService.find(rawRefreshToken, TokenType.REFRESH)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        User user = token.getUser();
        if (token.getUsedAt() != null) {
            log.warn("Refresh token reuse detected for user {} — revoking all sessions", user.getId());
            tokenService.revokeAll(user, TokenType.REFRESH);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Refresh token already used; please log in again");
        }
        if (!token.isUsable() || !user.isEnabled()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Refresh token expired; please log in again");
        }
        token.setUsedAt(Instant.now());
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        tokenService.find(rawRefreshToken, TokenType.REFRESH)
                .filter(t -> t.getUsedAt() == null)
                .ifPresent(t -> t.setUsedAt(Instant.now()));
    }

    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmailIgnoreCase(normalize(email))
                .filter(User::isEnabled)
                .ifPresent(user -> {
                    tokenService.revokeAll(user, TokenType.PASSWORD_RESET);
                    String token = tokenService.issue(user, TokenType.PASSWORD_RESET, props.passwordResetTokenTtl());
                    String link = props.passwordResetUrl() + "?token=" + token;
                    events.passwordResetRequested(user, token, link, props.passwordResetTokenTtl());
                });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = tokenService.consume(request.token(), TokenType.PASSWORD_RESET);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // The user proved they control the inbox, so the email counts as verified.
        user.setEmailVerified(true);
        tokenService.revokeAll(user, TokenType.REFRESH);
        events.passwordChanged(user);
        log.info("Password reset for user {}", user.getId());
    }

    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request) {
        User user = getUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        tokenService.revokeAll(user, TokenType.REFRESH);
        events.passwordChanged(user);
    }

    @Transactional(readOnly = true)
    public UserResponse me(String userId) {
        return UserResponse.from(getUser(userId));
    }

    private User getUser(String userId) {
        if (userId == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private TokenResponse issueTokens(User user) {
        String access = jwtService.issueAccessToken(user);
        String refresh = tokenService.issue(user, TokenType.REFRESH, props.refreshTokenTtl());
        return new TokenResponse(access, refresh, "Bearer", jwtService.accessTokenTtlSeconds(), UserResponse.from(user));
    }

    private void sendVerification(User user) {
        String token = tokenService.issue(user, TokenType.EMAIL_VERIFICATION, props.verificationTokenTtl());
        String link = props.publicBaseUrl() + "/api/v1/auth/verify-email?token=" + token;
        events.verificationRequested(user, link, props.verificationTokenTtl());
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        }
        return dummyHash;
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
