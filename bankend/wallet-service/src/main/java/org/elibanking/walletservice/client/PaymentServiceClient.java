package org.elibanking.walletservice.client;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.security.ServiceTokens;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Asks payment-service for a card intent, with a SERVICE token whose sub is wallet-service and aud is payment-service
 * (wallet-plan 3.5, step 2). payment-service keys the intent by the deposit id, so a repeat for the same deposit gets the same
 * intent back. See {@link AccountServiceClient} for why this is a RestClient.
 */
@Slf4j
@Component
public class PaymentServiceClient {

    /** The card intent for a deposit. {@code clientSecret} lets Stripe.js confirm the payment, so {@code toString} hides it. */
    public record Intent(String paymentIntentId, String clientSecret) {
        @Override
        public String toString() {
            return "Intent[paymentIntentId=" + paymentIntentId + ", clientSecret=****]";
        }
    }

    private final RestClient client;

    public PaymentServiceClient(@Value("${payment-service.url}") String baseUrl,
                                @Qualifier("paymentServiceTokens") ServiceTokens tokens) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    return execution.execute(request, body);
                })
                .build();
    }

    /** Creates the intent for a deposit. Any failure refuses the request with 503; the deposit can be resumed on a retry. */
    public Intent createIntent(String depositId, String amount, String currency) {
        JsonNode body;
        try {
            body = client.post()
                    .uri("/internal/v1/payments/intents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("depositId", depositId, "amount", amount, "currency", currency))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("payment-service did not create the intent for deposit {}: {}", depositId, e.getClass().getSimpleName());
            throw unavailable();
        }
        if (body == null || body.path("paymentIntentId").asText().isEmpty()) {
            throw unavailable();
        }
        return new Intent(body.path("paymentIntentId").asText(), body.path("clientSecret").asText());
    }

    private static RequestRefusedException unavailable() {
        return new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                "Card payments are unavailable right now. Try again shortly.");
    }
}
