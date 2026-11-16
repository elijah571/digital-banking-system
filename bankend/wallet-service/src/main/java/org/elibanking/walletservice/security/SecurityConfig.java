package org.elibanking.walletservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.elibanking.common.security.AdminRbac;
import org.elibanking.common.security.AuditorReadOnlyFilter;

/**
 * Who may call what (wallet-plan 3.7):
 * <ul>
 *   <li>health and API docs: public;</li>
 *   <li>{@code POST /internal/v1/pin/verify}: transaction-service only ({@link CallerAllowList});</li>
 *   <li>any other {@code /internal/v1/**}: denied for every caller;</li>
 *   <li>{@code /api/**}: user tokens only. A service token is refused with 403 there;</li>
 *   <li>no valid token: 401.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    AuthenticationManagerResolver<HttpServletRequest> bearerTokens)
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
                        // The transfer PIN check: transaction-service only (wallet-plan 3.7, threat model T8).
                        .requestMatchers(HttpMethod.POST, "/internal/v1/pin/verify")
                        .access(CallerAllowList.only(CallerAllowList.TRANSACTION, CallerAllowList.CRYPTO))
                        .requestMatchers(HttpMethod.POST, "/internal/v1/name-enquiries/validate", "/internal/v1/outbound-transfers")
                        .access(CallerAllowList.only(CallerAllowList.TRANSACTION))
                        .requestMatchers(HttpMethod.GET, "/internal/v1/audit")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .requestMatchers("/internal/v1/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/payouts").hasAuthority(AdminRbac.PAYOUTS_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/payouts/*/reroute", "/api/v1/admin/payouts/*/cancel-and-refund").hasAuthority(AdminRbac.PAYOUTS_APPROVE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/audit").hasAuthority(AdminRbac.AUDIT_LOGS_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/wallet/withdrawals/*/approve", "/api/v1/wallet/withdrawals/*/reject").hasAuthority(AdminRbac.PAYOUTS_APPROVE)
                        .requestMatchers("/api/v1/admin/**").hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/**").access(CallerAllowList.users())
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(bearerTokens));
        http.addFilterAfter(new AuditorReadOnlyFilter(), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
