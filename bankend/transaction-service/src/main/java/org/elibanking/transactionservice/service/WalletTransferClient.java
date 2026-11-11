package org.elibanking.transactionservice.service;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.common.web.RequestIdContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** SERVICE-authenticated wallet calls for the transaction-service external-transfer route. */
@Component
public class WalletTransferClient {

    public record Name(String accountName) {
    }

    public record Reply(int status, String body) {
    }

    private final RestClient wallet;
    private final ServiceTokens tokens;
    private final ObjectMapper mapper;

    public WalletTransferClient(@Value("${wallet-service.url}") String walletUrl,
                                @Qualifier("walletServiceTokens") ServiceTokens tokens, ObjectMapper mapper) {
        this.wallet = RestClient.builder().baseUrl(walletUrl).build();
        this.tokens = tokens;
        this.mapper = mapper;
    }

    public Name validate(String userId, String reference, String bankCode, String accountNumber) {
        try {
            JsonNode body = wallet.post().uri("/internal/v1/name-enquiries/validate")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .headers(RequestIdContext::apply)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userId", userId, "nameEnquiryRef", reference, "bankCode", bankCode,
                            "accountNumber", accountNumber))
                    .retrieve().body(JsonNode.class);
            if (body == null || body.path("accountName").asText().isBlank()) {
                throw new IllegalStateException("wallet-service did not validate the name enquiry");
            }
            return new Name(body.path("accountName").asText());
        } catch (RestClientResponseException e) {
            throw downstream(e);
        } catch (RestClientException e) {
            throw new WalletUnavailableException();
        }
    }

    public Reply transfer(String idempotencyKey, String userId, String accountNumber, String destinationAccountNumber,
                          String accountName, String amount, String bankCode, String reference, String narration, String pin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("accountName", accountName);
        body.put("accountNumber", accountNumber);
        body.put("destinationAccountNumber", destinationAccountNumber);
        body.put("amount", amount);
        body.put("bankCode", bankCode);
        body.put("nameEnquiryRef", reference);
        body.put("narration", narration);
        body.put("pin", pin);
        try {
            ResponseEntity<String> response = wallet.post().uri("/internal/v1/outbound-transfers")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .headers(RequestIdContext::apply)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(String.class);
            return new Reply(response.getStatusCode().value(), response.getBody());
        } catch (RestClientResponseException e) {
            throw downstream(e);
        } catch (RestClientException e) {
            throw new WalletUnavailableException();
        }
    }

    private RuntimeException downstream(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String detail = "Wallet service rejected the transfer";
        try {
            JsonNode body = mapper.readTree(e.getResponseBodyAsString());
            detail = body.path("detail").asText(detail);
        } catch (Exception ignored) {
            // Keep the generic response when wallet-service did not return problem+json.
        }
        if (status >= 500) {
            return new WalletUnavailableException();
        }
        return new WalletTransferRejectedException(status, detail);
    }
}
