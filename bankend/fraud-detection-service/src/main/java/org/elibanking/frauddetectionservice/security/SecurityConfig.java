package org.elibanking.frauddetectionservice.security;

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
 * The fraud check is internal (wallet-plan 3.5, step 3): only wallet-service may call it, with a service token. Everything else is refused,
 * apart from the health and info endpoints that the container checks.
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
                        .requestMatchers(HttpMethod.POST, "/internal/v1/fraud/check")
                        .access(CallerAllowList.only(CallerAllowList.WALLET))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(internalTokenDecoder)));
        return http.build();
    }

    @Bean
    SecurityFilterChain publicSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
