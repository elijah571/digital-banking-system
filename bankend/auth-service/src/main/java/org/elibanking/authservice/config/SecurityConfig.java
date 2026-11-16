package org.elibanking.authservice.config;

import org.elibanking.authservice.security.CallerAllowList;
import org.elibanking.common.security.AdminJwtAuthenticationConverter;
import org.elibanking.common.security.AdminRbac;
import org.elibanking.common.security.AuditorReadOnlyFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Who may call what (wallet-plan 3.7, threat model T8).
 * <ul>
 *   <li>{@code /internal/**}: a service token only. {@code POST /internal/v1/auth/verify-password} takes wallet-service and
 *       no other caller; every other internal path is denied. No token is 401, a wrong caller is 403.</li>
 *   <li>Auth flows are public; profiles and password changes require a user JWT; user administration requires ADMIN.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
                        .requestMatchers(HttpMethod.POST, "/internal/v1/auth/verify-password")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .requestMatchers(HttpMethod.GET, "/internal/v1/audit")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .requestMatchers(HttpMethod.GET, "/internal/v1/notifications/users/*")
                        .access(CallerAllowList.only(CallerAllowList.NOTIFICATION))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(internalTokenDecoder)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicSecurity(HttpSecurity http, JwtDecoder userTokenDecoder) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/resend-verification",
                                "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/verify-email").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/**").hasAnyAuthority(AdminRbac.CUSTOMERS_READ, AdminRbac.ADMIN_USERS_READ)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/**").hasAnyAuthority(AdminRbac.CUSTOMERS_MANAGE, AdminRbac.ADMIN_USERS_MANAGE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/change-password").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        .decoder(userTokenDecoder)
                        .jwtAuthenticationConverter(new AdminJwtAuthenticationConverter())));
        http.addFilterAfter(new AuditorReadOnlyFilter(), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder userTokenDecoder(@Value("${auth.jwt-secret:}") String secret,
                                @Value("${auth.jwt-issuer}") String issuer) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (same value as auth-service issuer)");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

}
