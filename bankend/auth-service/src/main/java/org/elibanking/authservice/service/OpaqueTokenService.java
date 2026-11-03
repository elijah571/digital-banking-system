package org.elibanking.authservice.service;

import lombok.RequiredArgsConstructor;
import org.elibanking.authservice.entity.AuthToken;
import org.elibanking.authservice.entity.TokenType;
import org.elibanking.authservice.entity.User;
import org.elibanking.authservice.exception.AuthException;
import org.elibanking.authservice.repository.AuthTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/** Random single-use tokens (verification, reset, refresh). Only their SHA-256 hash is persisted. */
@Service
@RequiredArgsConstructor
public class OpaqueTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthTokenRepository tokenRepository;

    /** Creates a token and returns the raw value (the only time it exists in clear text). */
    public String issue(User user, TokenType type, Duration ttl) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        AuthToken token = new AuthToken();
        token.setTokenHash(hash(raw));
        token.setType(type);
        token.setUser(user);
        token.setExpiresAt(Instant.now().plus(ttl));
        tokenRepository.save(token);
        return raw;
    }

    /** Marks the token as used and returns its user; fails with 400 if unknown, used or expired. */
    public User consume(String raw, TokenType type) {
        AuthToken token = find(raw, type)
                .filter(AuthToken::isUsable)
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST, "Invalid or expired token"));
        token.setUsedAt(Instant.now());
        return token.getUser();
    }

    public Optional<AuthToken> find(String raw, TokenType type) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return tokenRepository.findByTokenHashAndType(hash(raw), type);
    }

    public void revokeAll(User user, TokenType type) {
        tokenRepository.revokeAll(user, type, Instant.now());
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
