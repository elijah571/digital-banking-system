package org.elibanking.accountservice;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Mints tokens the way auth-service and the calling services do, for tests only. */
public final class TestTokens {

    /** Test values for the two secrets. Both are at least 32 bytes, as the application requires. */
    public static final String USER_SECRET = "user-secret-for-tests-0123456789abcdef";
    public static final String SERVICE_SECRET = "service-secret-for-tests-0123456789abcdef";

    public static final String USER_ISSUER = "elibanking-auth";
    public static final String SERVICE_ISSUER = "elibanking-internal";
    public static final String AUDIENCE = "account-service";

    private TestTokens() {
    }

    public static String userToken(String userId, String role) {
        return sign(USER_SECRET, USER_ISSUER, null, userId, role, Duration.ofMinutes(10));
    }

    public static String expiredUserToken(String userId) {
        return sign(USER_SECRET, USER_ISSUER, null, userId, "USER", Duration.ofMinutes(-5));
    }

    /** A service token for account-service, the way a calling service would mint it. */
    public static String serviceToken() {
        return serviceTokenFor(AUDIENCE);
    }

    public static String serviceTokenFor(String audience) {
        return sign(SERVICE_SECRET, SERVICE_ISSUER, audience, "transaction-service", "SERVICE", Duration.ofMinutes(5));
    }

    /** A user-issuer token signed with the service secret: the signature does not match the user decoder. */
    public static String userTokenWithTheWrongSecret(String userId) {
        return sign(SERVICE_SECRET, USER_ISSUER, null, userId, "ADMIN", Duration.ofMinutes(10));
    }

    private static String sign(String secret, String issuer, String audience, String subject, String role,
                               Duration lifetime) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .issueTime(Date.from(now.minus(Duration.ofMinutes(1))))
                .expirationTime(Date.from(now.plus(lifetime)))
                .claim("role", role)
                .claim("email", subject + "@example.test");
        if (audience != null) {
            claims.audience(audience);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign a test token", e);
        }
    }
}
