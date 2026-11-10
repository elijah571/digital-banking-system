package org.elibanking.transactionservice.config;

import feign.RequestInterceptor;
import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Every call from transaction-service to account-service carries a SERVICE token, with {@code sub = transaction-service}
 * (wallet-plan 3.7). The token is minted locally and reused until it nears expiry.
 */
@Configuration
public class AccountServiceAuth {

    @Bean
    public ServiceTokens accountServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
                                              @Value("${internal-auth.subject}") String subject,
                                              @Value("${internal-auth.audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }

    @Bean
    public RequestInterceptor accountServiceTokenInterceptor(ServiceTokens accountServiceTokens) {
        return template -> {
            template.header(HttpHeaders.AUTHORIZATION, "Bearer " + accountServiceTokens.token());
            currentRequestId().ifPresent(requestId -> template.header("X-Request-Id", requestId));
        };
    }

    private static Optional<String> currentRequestId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        String requestId = attributes.getRequest().getHeader("X-Request-Id");
        if (requestId == null || requestId.isEmpty() || requestId.length() > 100
                || !requestId.chars().allMatch(character -> character > 0x20 && character < 0x7f)) {
            return Optional.empty();
        }
        return Optional.of(requestId);
    }
}
