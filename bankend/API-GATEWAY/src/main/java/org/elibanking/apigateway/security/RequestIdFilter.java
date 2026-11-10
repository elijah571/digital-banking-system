package org.elibanking.apigateway.security;

import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Validates or assigns the request correlation id before security and routing. */
@Component
public class RequestIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    private static final String INVALID_ID_PROBLEM = """
            {"type":"about:blank","title":"Bad Request","status":400,"detail":"X-Request-Id must contain 1 to 100 visible ASCII characters"}
            """.strip();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst(HEADER);
        boolean valid = supplied == null || isValid(supplied);
        String requestId = supplied != null && valid ? supplied : UUID.randomUUID().toString();

        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        if (!valid) {
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            DataBuffer body = response.bufferFactory().wrap(INVALID_ID_PROBLEM.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(body));
        }

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private static boolean isValid(String value) {
        if (value.isEmpty() || value.length() > 100) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x21 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
