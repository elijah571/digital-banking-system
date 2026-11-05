package org.elibanking.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Mints the short-lived service token that a calling service sends to another service's internal API (wallet-plan 3.7).
 * The token is HS256, signed with INTERNAL_JWT_SECRET, with {@code iss=elibanking-internal}, {@code role=SERVICE},
 * {@code sub} = the calling service's name, and {@code aud} = the service being called. It lasts 5 minutes. The same
 * token is reused until a minute before it expires, and then a new one is minted.
 *
 * <p>The callee checks the issuer, audience, role and subject, so a token from one service cannot be used as another's.
 */
public final class ServiceTokens {

    public static final String ISSUER = "elibanking-internal";
    public static final String ROLE = "SERVICE";

    static final Duration LIFETIME = Duration.ofMinutes(5);
    static final Duration RENEW_BEFORE_EXPIRY = Duration.ofMinutes(1);

    private static final int SECRET_MIN_BYTES = 32;
    private static final int SUBJECT_MAX_LENGTH = 100;

    private final MACSigner signer;
    private final String subject;
    private final String audience;
    private final Clock clock;
    private Issued current;

    private record Issued(String token, Instant renewAt) {
    }

    public ServiceTokens(String secret, String subject, String audience) {
        this(secret, subject, audience, Clock.systemUTC());
    }

    ServiceTokens(String secret, String subject, String audience, Clock clock) {
        byte[] key = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < SECRET_MIN_BYTES) {
            throw new IllegalArgumentException("the service token secret (INTERNAL_JWT_SECRET) must be set and at least "
                    + SECRET_MIN_BYTES + " bytes long");
        }
        if (subject == null || subject.isBlank() || subject.length() > SUBJECT_MAX_LENGTH) {
            throw new IllegalArgumentException("the service name (subject) must be 1 to " + SUBJECT_MAX_LENGTH
                    + " characters");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("the audience (the service being called) must be set");
        }
        try {
            this.signer = new MACSigner(key);
        } catch (JOSEException e) {
            throw new IllegalArgumentException("the service token secret is not usable for HS256", e);
        }
        this.subject = subject;
        this.audience = audience;
        this.clock = clock;
    }

    /** A token ready for an {@code Authorization: Bearer} header, without the prefix. */
    public synchronized String token() {
        Instant now = clock.instant();
        if (current == null || !now.isBefore(current.renewAt())) {
            current = mint(now);
        }
        return current.token();
    }

    private Issued mint(Instant now) {
        Instant expires = now.plus(LIFETIME);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expires))
                .claim("role", ROLE)
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return new Issued(jwt.serialize(), expires.minus(RENEW_BEFORE_EXPIRY));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign a service token", e);
        }
    }
}
