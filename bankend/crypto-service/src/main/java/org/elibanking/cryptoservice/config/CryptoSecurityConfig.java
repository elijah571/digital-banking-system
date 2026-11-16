package org.elibanking.cryptoservice.config;

import org.elibanking.common.security.AdminJwtAuthenticationConverter;
import org.elibanking.common.security.AdminRbac;
import org.elibanking.common.security.AuditorReadOnlyFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class CryptoSecurityConfig {
    @Bean
    SecurityFilterChain cryptoSecurity(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/crypto/webhooks/fireblocks").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/crypto/admin/assets").hasAuthority(AdminRbac.CRYPTO_WALLETS_READ)
                        .requestMatchers(HttpMethod.GET, "/api/v1/crypto/admin/reviews").hasAuthority(AdminRbac.CRYPTO_TRANSACTIONS_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/crypto/admin/transactions/*/approve", "/api/v1/crypto/admin/transactions/*/reject").hasAuthority(AdminRbac.CRYPTO_OPERATIONS_APPROVE)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/crypto/admin/assets").hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(new AdminJwtAuthenticationConverter())));
        http.addFilterAfter(new AuditorReadOnlyFilter(), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${auth.jwt-secret:}") String secret, @Value("${auth.jwt-issuer}") String issuer) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalStateException("JWT_SECRET must be set and at least 32 bytes");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

}
