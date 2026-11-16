package org.elibanking.apigateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Prevents an auditor token from reaching any mutating API route. */
@Component
public class AuditorReadOnlyGlobalFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .filter(this::isAuditor)
                .hasElement()
                .flatMap(auditor -> auditor && !isReadOnly(exchange.getRequest().getMethod())
                        ? forbidden(exchange) : chain.filter(exchange));
    }

    private boolean isAuditor(JwtAuthenticationToken authentication) {
        java.util.List<String> roles = authentication.getToken().getClaimAsStringList("roles");
        return roles != null && roles.contains("ROLE_AUDITOR");
    }

    private static boolean isReadOnly(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method) || HttpMethod.OPTIONS.equals(method);
    }

    private static Mono<Void> forbidden(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
        var body = response.bufferFactory().wrap("{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Auditor access is read-only\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(body));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
