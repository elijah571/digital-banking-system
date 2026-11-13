package org.elibanking.notificationservice.client;

import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Retrieves only the email/name needed to deliver a customer notice. */
@Component
public class AuthRecipientClient {

    public record Recipient(String email, String fullName) {}

    private final RestClient client;
    private final ServiceTokens tokens;

    public AuthRecipientClient(RestClient.Builder builder,
                               ServiceTokens notificationServiceTokens,
                               @Value("${auth-service.url}") String authServiceUrl) {
        this.client = builder.baseUrl(authServiceUrl).build();
        this.tokens = notificationServiceTokens;
    }

    public Recipient get(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            return client.get()
                    .uri("/internal/v1/notifications/users/{userId}", userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .retrieve()
                    .body(Recipient.class);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Could not resolve the customer email recipient", failure);
        }
    }
}
