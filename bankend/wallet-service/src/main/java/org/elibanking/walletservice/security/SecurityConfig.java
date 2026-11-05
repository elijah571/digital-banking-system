package org.elibanking.walletservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what (wallet-plan 3.7). Slice 4.1 has no endpoints yet, so the rules are the defaults:
 * <ul>
 *   <li>health and API docs: public;</li>
 *   <li>{@code /internal/v1/**}: denied for every caller until an endpoint is added with its own allow-list
 *       ({@link CallerAllowList});</li>
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
                        .requestMatchers("/internal/v1/**").denyAll()
                        .requestMatchers("/api/**").access(CallerAllowList.users())
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(bearerTokens));
        return http.build();
    }
}
