package org.elibanking.cryptoservice.config;

import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoClientConfig {
    @Bean @Qualifier("accountServiceTokens")
    ServiceTokens accountServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
            @Value("${internal-auth.subject}") String subject, @Value("${internal-auth.account-audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }
    @Bean @Qualifier("walletServiceTokens")
    ServiceTokens walletServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
            @Value("${internal-auth.subject}") String subject, @Value("${internal-auth.wallet-audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }
    @Bean @Qualifier("fraudServiceTokens")
    ServiceTokens fraudServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
            @Value("${internal-auth.subject}") String subject, @Value("${internal-auth.fraud-audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }
}
