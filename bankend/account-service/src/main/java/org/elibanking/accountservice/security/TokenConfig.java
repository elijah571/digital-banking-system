package org.elibanking.accountservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Two kinds of bearer token, told apart by the issuer (wallet-plan 3.7):
 * <ul>
 *   <li>user tokens from auth-service: HS256 with JWT_SECRET, {@code iss=elibanking-auth}, role from the {@code role} claim;</li>
 *   <li>service tokens: HS256 with INTERNAL_JWT_SECRET, {@code iss=elibanking-internal}, {@code role=SERVICE}, and
 *       {@code aud} containing this service.</li>
 * </ul>
 * A token of one kind never validates as the other, because the secrets differ and the issuer and role are checked.
 */
@Configuration
class TokenConfig {

    @Bean
    AuthenticationManagerResolver<HttpServletRequest> bearerTokens(
            @Value("${auth.jwt-secret:}") String userSecret,
            @Value("${auth.jwt-issuer}") String userIssuer,
            @Value("${internal-auth.jwt-secret:}") String serviceSecret,
            @Value("${internal-auth.jwt-issuer}") String serviceIssuer,
            @Value("${internal-auth.audience}") String audience) {
        JwtAuthenticationConverter authorities = rolesAsAuthorities();

        JwtAuthenticationProvider user = new JwtAuthenticationProvider(
                hs256(userSecret, "JWT_SECRET", JwtValidators.createDefaultWithIssuer(userIssuer)));
        user.setJwtAuthenticationConverter(authorities);

        JwtAuthenticationProvider service = new JwtAuthenticationProvider(
                hs256(serviceSecret, "INTERNAL_JWT_SECRET", serviceValidator(serviceIssuer, audience)));
        service.setJwtAuthenticationConverter(authorities);

        Map<String, AuthenticationManager> managers = Map.of(
                userIssuer, new ProviderManager(user),
                serviceIssuer, new ProviderManager(service));
        AuthenticationManagerResolver<String> byIssuer = issuer -> {
            AuthenticationManager manager = managers.get(issuer);
            if (manager == null) {
                throw new InvalidBearerTokenException("unknown token issuer");
            }
            return manager;
        };
        return new JwtIssuerAuthenticationManagerResolver(byIssuer);
    }

    static NimbusJwtDecoder hs256(String secret, String envName, OAuth2TokenValidator<Jwt> validator) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException(envName + " must be set and at least 32 bytes long");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(validator);
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> serviceValidator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>("role", "SERVICE"::equals));
    }

    /** {@code "role": "ADMIN"} becomes the authority {@code ROLE_ADMIN}, as in the gateway. */
    static JwtAuthenticationConverter rolesAsAuthorities() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
