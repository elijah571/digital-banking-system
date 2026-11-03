package org.elibanking.apigateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Passes the authenticated identity to the downstream services as trusted headers.
 * Any X-User-* header sent by the client is removed first, so it cannot be spoofed.
 * Downstream services must only be reachable through the gateway (see compose.prod.yaml).
 */
@Component
public class UserIdentityHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLE = "X-User-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> Optional.of(((JwtAuthenticationToken) p).getToken()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(jwt -> chain.filter(exchange.mutate().request(withIdentity(exchange.getRequest(), jwt)).build()));
    }

    private static ServerHttpRequest withIdentity(ServerHttpRequest request, Optional<Jwt> jwt) {
        return request.mutate().headers(h -> {
            h.remove(USER_ID);
            h.remove(USER_EMAIL);
            h.remove(USER_ROLE);
            jwt.ifPresent(t -> {
                h.set(USER_ID, t.getSubject());
                h.set(USER_EMAIL, t.getClaimAsString("email"));
                h.set(USER_ROLE, t.getClaimAsString("role"));
            });
        }).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
