package org.elibanking.accountservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what (wallet-plan 3.7, 8.18). Service tokens are checked by their {@code sub} through
 * {@link CallerAllowList}:
 * <ul>
 *   <li>health, info and API docs: public;</li>
 *   <li>{@code POST /internal/v1/ledger/entries} and {@code …/reverse}: transaction-service and wallet-service;</li>
 *   <li>other {@code GET /internal/v1/ledger/**} reads: those two, and fraud-detection-service and payment-service;</li>
 *   <li>any other {@code /internal/v1/**} path: denied;</li>
 *   <li>{@code PUT …/deduct} and {@code …/credit} (removed in 3c): transaction-service only;</li>
 *   <li>{@code GET /api/v1/accounts/{n}} and {@code …/balance}: users (ownership is checked in the controller), and the
 *       services that read accounts;</li>
 *   <li>{@code GET /api/v1/accounts} (all accounts): ADMIN;</li>
 *   <li>{@code /api/v1/admin/**}: ADMIN;</li>
 *   <li>everything else under /api: any authenticated caller.</li>
 * </ul>
 * Sessions are stateless. No valid credentials gives 401. A valid token that the rule refuses gives 403.
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
                        // Internal ledger API: writes from transaction and wallet only; reads also from fraud and payment.
                        .requestMatchers(HttpMethod.POST, "/internal/v1/ledger/entries",
                                "/internal/v1/ledger/entries/*/reverse")
                        .access(CallerAllowList.only(CallerAllowList.TRANSACTION, CallerAllowList.WALLET))
                        .requestMatchers(HttpMethod.GET, "/internal/v1/ledger/**")
                        .access(CallerAllowList.only(CallerAllowList.TRANSACTION, CallerAllowList.WALLET,
                                CallerAllowList.FRAUD, CallerAllowList.PAYMENT))
                        .requestMatchers("/internal/v1/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts").hasRole("ADMIN")
                        // The old deduct and credit paths (removed in 3c): transaction-service only.
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/deduct", "/api/v1/accounts/*/credit")
                        .access(CallerAllowList.only(CallerAllowList.TRANSACTION))
                        // Account reads: users (the controller checks ownership) and the services that read accounts.
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts/*", "/api/v1/accounts/*/balance")
                        .access(AuthorizationManagers.anyOf(CallerAllowList.users(),
                                CallerAllowList.only(CallerAllowList.TRANSACTION, CallerAllowList.WALLET,
                                        CallerAllowList.FRAUD, CallerAllowList.PAYMENT)))
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(bearerTokens))
                .addFilterBefore(new LegacyHeaderFilter(legacyHeadersEnabled), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
