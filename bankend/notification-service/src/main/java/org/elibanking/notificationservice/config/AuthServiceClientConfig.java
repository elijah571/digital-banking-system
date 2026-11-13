package org.elibanking.notificationservice.config;

import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AuthServiceClientConfig {

    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    ServiceTokens notificationServiceTokens(@Value("${internal-auth.jwt-secret:}") String secret) {
        return new ServiceTokens(secret, "notification-service", "auth-service");
    }
}
