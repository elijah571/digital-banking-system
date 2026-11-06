package org.elibanking.transactionservice.config;

import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The SERVICE token for the PIN check at wallet-service (wallet-plan 3.7): sub = transaction-service, aud = wallet-service. It is a
 * separate bean from the account one, which has a different audience.
 */
@Configuration
public class WalletPinAuth {

    /** The client builder for the wallet call. Boot 4 does not supply one to this service, so it is declared here. */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public ServiceTokens walletServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret,
                                             @Value("${internal-auth.subject}") String subject,
                                             @Value("${wallet-service.audience}") String audience) {
        return new ServiceTokens(secret, subject, audience);
    }
}
