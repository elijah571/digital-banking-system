package org.elibanking.apigateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * All authentication and role checks happen here, in one place.
 * <ul>
 *   <li>public: auth flows (register, login, verify, reset…), Stripe webhook, health, Swagger</li>
 *   <li>ADMIN: user management, block / deduct / credit, list all accounts / transactions, /api/v1/admin/** (account-service admin endpoints), actuator</li>
 *   <li>everything else under /api: any logged-in user (ownership is checked by each service)</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_AUTH = {
            "/api/v1/auth/register", "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification",
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
            "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password"
    };
    private static final String[] PUBLIC_DOCS = {
            "/swagger-ui.html", "/swagger-ui/**", "/webjars/**", "/v3/api-docs/**", "/docs/**"
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)          // stateless JWT API, no cookies
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(PUBLIC_AUTH).permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments/webhook").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers(PUBLIC_DOCS).permitAll()
                        .pathMatchers("/api/v1/users/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/accounts", "/api/v1/transactions").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/v1/accounts/*/block", "/api/v1/accounts/*/deduct",
                                "/api/v1/accounts/*/credit").hasRole("ADMIN")
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((exchange, e) ->
                                problem(exchange, HttpStatus.UNAUTHORIZED, "Missing, invalid or expired access token. Log in via POST /api/v1/auth/login"))
                        .accessDeniedHandler((exchange, e) ->
                                problem(exchange, HttpStatus.FORBIDDEN, "You do not have permission to do this")))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((exchange, e) ->
                                problem(exchange, HttpStatus.UNAUTHORIZED, "Missing, invalid or expired access token. Log in via POST /api/v1/auth/login"))
                        .accessDeniedHandler((exchange, e) ->
                                problem(exchange, HttpStatus.FORBIDDEN, "You do not have permission to do this")))
                .build();
    }

    /** HS256 tokens signed by auth-service with the shared JWT_SECRET; issuer and expiry are validated. */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(@Value("${auth.jwt-secret:}") String secret,
                                         @Value("${auth.jwt-issuer}") String issuer) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 bytes (same value as auth-service)");
        }
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /** "role": "ADMIN" claim → authority ROLE_ADMIN. */
    private ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    private static Mono<Void> problem(ServerWebExchange exchange, HttpStatus status, String detail) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\",\"instance\":\"" + exchange.getRequest().getPath().value() + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
