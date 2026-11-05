package org.elibanking.frauddetectionservice.config;

import feign.RequestInterceptor;
import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * Every call from fraud-detection to account-service carries a SERVICE token, with {@code sub = fraud-detection-service}
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
        return template -> template.header(HttpHeaders.AUTHORIZATION, "Bearer " + accountServiceTokens.token());
    }
}
