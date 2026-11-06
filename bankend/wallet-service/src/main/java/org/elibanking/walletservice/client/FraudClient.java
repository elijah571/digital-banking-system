package org.elibanking.walletservice.client;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Asks fraud-detection-service about an outbound payout, with a SERVICE token whose sub is wallet-service and aud is
 * fraud-detection-service (wallet-plan 3.5, step 3). An answer that cannot be had is empty, and the caller fails closed: the payout
 * waits for a person. See {@link AccountServiceClient} for why this is a RestClient.
 */
@Slf4j
@Component
public class FraudClient {

    /** The fraud answer for a payout: whether it is flagged, and why. */
    public record FraudResult(boolean fraud, String reason) {
    }

    private final RestClient client;

    public FraudClient(@Value("${fraud-service.url}") String baseUrl,
                       @Qualifier("fraudServiceTokens") ServiceTokens tokens) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    return execution.execute(request, body);
                })
                .build();
    }

    /** The check for a payout. Empty when fraud-detection-service cannot answer, and the payout then waits for a person. */
    public Optional<FraudResult> check(String type, String accountNumber, BigDecimal amount) {
        JsonNode body;
        try {
            body = client.post()
                    .uri("/internal/v1/fraud/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", type, "accountNumber", accountNumber, "amount", amount.toPlainString()))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("fraud-detection-service did not answer the payout check: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (body == null || !body.hasNonNull("fraud")) {
            return Optional.empty();
        }
        String reason = body.hasNonNull("reason") ? body.get("reason").asText() : null;
        return Optional.of(new FraudResult(body.get("fraud").asBoolean(), reason));
    }
}
