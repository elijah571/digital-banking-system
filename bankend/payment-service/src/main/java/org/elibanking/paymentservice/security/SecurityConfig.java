package org.elibanking.paymentservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * User endpoints need a user token from auth-service (wallet-plan 3.7, D8). This service checks the token itself: the gateway's
 * X-User-* headers are not trusted. The Stripe webhook is public, because Stripe calls it and it checks its own signature.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Internal calls from wallet-service (wallet-plan 3.7): a service token only, and only the callers on the allow-list.
     * This chain comes first, so an /internal path never reaches the user chain below.
     */
    @Bean
    @Order(1)
    SecurityFilterChain internalSecurity(HttpSecurity http, JwtDecoder internalTokenDecoder) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/internal/v1/payments/intents",
                                "/internal/v1/payments/intents/*/cancel")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .requestMatchers(HttpMethod.GET, "/internal/v1/banks")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .requestMatchers(HttpMethod.POST, "/internal/v1/accounts/resolve")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .requestMatchers(HttpMethod.POST, "/internal/v1/payouts")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .requestMatchers(HttpMethod.GET, "/internal/v1/payouts/*")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(internalTokenDecoder)));
        return http.build();
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder userTokens) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/flutterwave").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(userTokens)));
        return http.build();
    }

    /** HS256 tokens from auth-service: the issuer is checked, and so is the subject, which is the user id. */
    @Bean
    JwtDecoder userTokens(@Value("${auth.jwt-secret:}") String secret, @Value("${auth.jwt-issuer}") String issuer) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 bytes long (same value as auth-service)");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // A token with no subject would be read as an internal call, and internal calls skip ownership checks. Refuse it.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank())));
        return decoder;
    }
}
