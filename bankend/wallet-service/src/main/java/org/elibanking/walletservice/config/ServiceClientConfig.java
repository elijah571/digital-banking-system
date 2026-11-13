package org.elibanking.walletservice.config;

import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One SERVICE token source per callee (wallet-plan 3.7). Each token has sub=wallet-service and an aud of the callee, so
 * account-service and payment-service refuse a token that was minted for another service.
 */
@Configuration
public class ServiceClientConfig {

    @Bean
    ServiceTokens accountServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
                                       @Value("${internal-auth.subject}") String subject,
                                       @Value("${account-service.audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }

    @Bean
    ServiceTokens paymentServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
                                       @Value("${internal-auth.subject}") String subject,
                                       @Value("${payment-service.audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }

    @Bean
    ServiceTokens fraudServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
                                     @Value("${internal-auth.subject}") String subject,
                                     @Value("${fraud-service.audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }

    @Bean
    ServiceTokens transactionServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
                                           @Value("${internal-auth.subject}") String subject,
                                           @Value("${transaction-service.audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }
}
