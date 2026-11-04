package org.elibanking.common.security;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTokensTest {

    private static final String SECRET = "banking-common-test-secret-0123456789";
    private static final Instant START = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void theTokenNamesTheCallerAndTheCalleeAndLastsFiveMinutes() throws Exception {
        ServiceTokens tokens = new ServiceTokens(SECRET, "transaction-service", "account-service", new Ticking(START));

        SignedJWT jwt = SignedJWT.parse(tokens.token());
        assertTrue(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8))), "signed with the shared secret");
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals(ServiceTokens.ISSUER, claims.getIssuer());
        assertEquals("transaction-service", claims.getSubject(), "the subject is the calling service");
        assertEquals(List.of("account-service"), claims.getAudience(), "the audience is the service being called");
        assertEquals("SERVICE", claims.getStringClaim("role"));
        assertEquals(Duration.ofMinutes(5).getSeconds(), Duration.between(
                claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()).getSeconds());
    }

    @Test
    void theSameTokenIsReusedUntilItIsNearlyExpiredThenRenewed() {
        Ticking clock = new Ticking(START);
        ServiceTokens tokens = new ServiceTokens(SECRET, "payment-service", "account-service", clock);

        String first = tokens.token();
        clock.at(START.plus(Duration.ofMinutes(1)));
        assertEquals(first, tokens.token(), "a token a minute old is reused");

        clock.at(START.plus(Duration.ofMinutes(4)));
        assertNotEquals(first, tokens.token(), "a token four minutes old is replaced before it expires");
    }

    @Test
    void aTokenIsNotMintedWithoutAUsableSecretNameOrAudience() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceTokens("too-short", "transaction-service", "account-service"), "secret under 32 bytes");
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceTokens(null, "transaction-service", "account-service"), "no secret");
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceTokens(SECRET, " ", "account-service"), "blank subject");
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceTokens(SECRET, "x".repeat(101), "account-service"), "subject over 100 characters");
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceTokens(SECRET, "transaction-service", ""), "blank audience");
    }

    /** A clock the test can move forward without sleeping. */
    private static final class Ticking extends Clock {

        private Instant now;

        Ticking(Instant now) {
            this.now = now;
        }

        void at(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
