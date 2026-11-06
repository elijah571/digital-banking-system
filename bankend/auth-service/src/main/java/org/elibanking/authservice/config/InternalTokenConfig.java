package org.elibanking.authservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes the service tokens that the internal API accepts (wallet-plan 3.7). A token must be HS256 with INTERNAL_JWT_SECRET,
 * carry this issuer, name this service in {@code aud}, have {@code role=SERVICE}, and have a subject of at most 100
 * characters. A user token never validates here, because its secret and issuer differ.
 */
@Configuration
class InternalTokenConfig {

    @Bean
    JwtDecoder internalTokenDecoder(@Value("${internal-auth.jwt-secret:}") String secret,
                                    @Value("${internal-auth.jwt-issuer}") String issuer,
                                    @Value("${internal-auth.audience}") String audience) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("INTERNAL_JWT_SECRET must be set and at least 32 bytes long");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>("role", "SERVICE"::equals),
                // The subject is the audit actor (wallet-plan 3.9); audit_log.actor is VARCHAR(100).
                new JwtClaimValidator<String>(JwtClaimNames.SUB,
                        sub -> sub != null && !sub.isBlank() && sub.length() <= 100)));
        return decoder;
    }
}
