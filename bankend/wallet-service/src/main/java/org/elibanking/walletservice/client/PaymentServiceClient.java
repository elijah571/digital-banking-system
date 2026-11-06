package org.elibanking.walletservice.client;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.security.ServiceTokens;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Asks payment-service to cancel a deposit's intent at Stripe. True when it is canceled now, or was before. False when Stripe will
     * not cancel it, for example because it has succeeded. Any other failure is refused with 503, and the caller tries again later.
     */
    public boolean cancelIntent(String paymentIntentId) {
        try {
            client.post()
                    .uri("/internal/v1/payments/intents/{paymentIntentId}/cancel", paymentIntentId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.Conflict refused) {
            return false;
        } catch (RestClientException e) {
            log.warn("payment-service did not cancel an intent: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    /** A bank the provider can send to (wallet-plan 8.4). */
    public record Bank(String name, String code) {
    }

    /** What the provider found for an account number: FOUND, NOT_FOUND or UNAVAILABLE, with the holder's name when it was found. */
    public record Resolution(String status, String accountName) {
    }

    /** The provider's banks, for the bank list (wallet-plan 8.4). */
    public List<Bank> listBanks() {
        JsonNode body;
        try {
            body = client.get().uri("/internal/v1/banks").retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("payment-service did not list the banks: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
        if (body == null || !body.isArray()) {
            throw unavailable();
        }
        List<Bank> banks = new ArrayList<>();
        for (JsonNode bank : body) {
            banks.add(new Bank(bank.path("name").asText(), bank.path("code").asText()));
        }
        return banks;
    }

    /** The provider's name enquiry for an account at another bank (wallet-plan 8.4). The account number is not logged. */
    public Resolution resolveAccount(String bankCode, String accountNumber) {
        JsonNode body;
        try {
            body = client.post()
                    .uri("/internal/v1/accounts/resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("bankCode", bankCode, "accountNumber", accountNumber))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("payment-service did not resolve an account: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
        if (body == null || !body.hasNonNull("status")) {
            throw unavailable();
        }
        String name = body.hasNonNull("accountName") ? body.get("accountName").asText() : null;
        return new Resolution(body.get("status").asText(), name);
    }

    /** The provider's answer to a send: its reference, and ACCEPTED, PENDING or FAILED (wallet-plan 8.7). */
    public record Send(String providerRef, String status) {
    }

    /**
     * Sends a payout under its key, out-{id}. The provider answers a repeat of a key with its first answer, so a retry is safe. Any failure
     * is refused with 503, and the processor tries again with the same key.
     */
    public Send sendPayout(String key, String amount, String bankCode, String accountNumber, String accountName, String narration) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("amount", amount);
        body.put("bankCode", bankCode);
        body.put("accountNumber", accountNumber);
        body.put("accountName", accountName);
        body.put("narration", narration);
        JsonNode answer;
        try {
            answer = client.post()
                    .uri("/internal/v1/payouts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("payment-service did not take the payout {}: {}", key, e.getClass().getSimpleName());
            throw unavailable();
        }
        if (answer == null || !answer.hasNonNull("status")) {
            throw unavailable();
        }
        return new Send(answer.path("providerRef").asText(), answer.get("status").asText());
    }

    /** The provider's status for a send: SUCCESS, PENDING, FAILED, REVERSED or NOT_FOUND. Asked, not believed from the send (wallet-plan 8.3). */
    public String payoutStatus(String key) {
        JsonNode answer;
        try {
            answer = client.get().uri("/internal/v1/payouts/{key}", key).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("payment-service did not answer the status of {}: {}", key, e.getClass().getSimpleName());
            throw unavailable();
        }
        if (answer == null || !answer.hasNonNull("status")) {
            throw unavailable();
        }
        return answer.get("status").asText();
    }

    private static RequestRefusedException unavailable() {
        return new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                "Card payments are unavailable right now. Try again shortly.");
    }
}
