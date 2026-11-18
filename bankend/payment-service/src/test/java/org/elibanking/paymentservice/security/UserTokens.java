package org.elibanking.paymentservice.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Mints user tokens the way auth-service does, for tests only. The secret is the one this service is configured with. */
public final class UserTokens {

    public static final String SECRET = "payment-user-secret-for-tests-0123456789";

    private UserTokens() {
    }

    public static String of(String subject, String role) {
        return sign(subject, role);
    }

    public static String auditor(String subject) {
        Instant now = Instant.now();
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("elibanking-auth").subject(subject)
                    .issueTime(Date.from(now.minus(Duration.ofMinutes(1))))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(10))))
                    .claim("role", "ADMIN").claim("roles", List.of("ROLE_AUDITOR"))
                    .claim("authorities", List.of("customers.read", "accounts.read", "kyc.read", "ledger.read",
                            "reconciliation.read", "transactions.read", "payouts.read", "crypto.wallets.read",
                            "crypto.transactions.read", "risk.alerts.read", "audit.logs.read"))
                    .claim("email", subject + "@example.test").build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign an auditor token", e);
        }
    }

    /** A valid, signed token with no subject. The service must refuse it. */
    public static String withoutSubject() {
        return sign(null, "USER");
    }

    private static String sign(String subject, String role) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("elibanking-auth")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(10))))
                .claim("role", role)
                .claim("email", subject + "@example.test");
        if (subject != null) {
            claims.subject(subject);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign a test token", e);
        }
    }
}
