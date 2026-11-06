package org.elibanking.walletservice.config;

import feign.RequestInterceptor;
import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * Every call from wallet-service to auth-service carries a SERVICE token whose {@code sub} is wallet-service (wallet-plan 3.7).
 * The token is minted here, and reused until it nears expiry.
 */
@Configuration
public class AuthClientConfig {

    @Bean
    public ServiceTokens authServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
                                           @Value("${internal-auth.subject}") String subject,
                                           @Value("${auth-service.audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }

    @Bean
    public RequestInterceptor authServiceTokenInterceptor(ServiceTokens authServiceTokens) {
        return template -> template.header(HttpHeaders.AUTHORIZATION, "Bearer " + authServiceTokens.token());
    }
}
