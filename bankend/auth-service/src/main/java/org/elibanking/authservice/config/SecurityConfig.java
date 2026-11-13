package org.elibanking.authservice.config;

import org.elibanking.authservice.security.CallerAllowList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what (wallet-plan 3.7, threat model T8).
 * <ul>
 *   <li>{@code /internal/**}: a service token only. {@code POST /internal/v1/auth/verify-password} takes wallet-service and
 *       no other caller; every other internal path is denied. No token is 401, a wrong caller is 403.</li>
 *   <li>Everything else: no authentication, as before this slice. The gateway checks user tokens.</li>
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
    SecurityFilterChain publicSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
