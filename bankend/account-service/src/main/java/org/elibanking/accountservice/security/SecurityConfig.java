package org.elibanking.accountservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what (wallet-plan 3.7, 8.18):
 * <ul>
 *   <li>health, info and API docs: public;</li>
 *   <li>{@code /internal/v1/**} (the ledger API): service tokens only, never the legacy path;</li>
 *   <li>{@code PUT …/deduct} and {@code …/credit}: ADMIN, a service token, or the legacy path when it is enabled;</li>
 *   <li>{@code GET /api/v1/accounts} (all accounts): ADMIN;</li>
 *   <li>{@code /api/v1/admin/**}: ADMIN;</li>
 *   <li>everything else under /api: any authenticated user or service token, or the legacy path for the old endpoints.</li>
 * </ul>
 * Sessions are stateless. Errors: no valid credentials gives 401, and a valid token without the role gives 403.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    AuthenticationManagerResolver<HttpServletRequest> bearerTokens,
                                    @Value("${account.legacy-headers.enabled:false}") boolean legacyHeadersEnabled)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/internal/v1/**").hasRole("SERVICE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/deduct", "/api/v1/accounts/*/credit")
                        .hasAnyRole("ADMIN", "SERVICE", LegacyHeaderFilter.LEGACY_ROLE)
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(bearerTokens))
                .addFilterBefore(new LegacyHeaderFilter(legacyHeadersEnabled), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
