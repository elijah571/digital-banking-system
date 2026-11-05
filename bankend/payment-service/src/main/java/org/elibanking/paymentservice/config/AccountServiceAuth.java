package org.elibanking.paymentservice.config;

import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service token payment-service sends to account-service, with {@code sub = payment-service} (wallet-plan 3.7). The
 * secret is shared with account-service and must be set; the context does not start without it.
 */
@Configuration
public class AccountServiceAuth {

    @Bean
    public ServiceTokens accountServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
                                              @Value("${internal-auth.subject}") String subject,
                                              @Value("${internal-auth.audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }
}
