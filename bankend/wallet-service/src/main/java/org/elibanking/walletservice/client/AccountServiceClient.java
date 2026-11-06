package org.elibanking.walletservice.client;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.security.ServiceTokens;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Reads an account from account-service with a SERVICE token whose sub is wallet-service (wallet-plan 3.5, 3.7). The owner and
 * the status come from the account read, and the currency from the ledger read. Both reads allow wallet-service. The account
 * number is never logged. An outage refuses the request with 503, so a deposit is not opened for an account that is not known.
 *
 * <p>This is a RestClient, not a Feign client. The auth-service interceptor in {@code AuthClientConfig} is a bean, and Feign
 * applies every interceptor bean to every client, so a Feign client here would also send the auth-service token.
 */
@Slf4j
@Component
public class AccountServiceClient {

    /** What a deposit needs to know about the account. */
    public record AccountCheck(String ownerId, String status, String currency) {
    }

    private final RestClient client;

    public AccountServiceClient(@Value("${account-service.url}") String baseUrl,
                                @Qualifier("accountServiceTokens") ServiceTokens tokens) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    return execution.execute(request, body);
                })
                .build();
    }

    public AccountCheck check(String accountNumber) {
        JsonNode account = get("/api/v1/accounts/{accountNumber}", accountNumber);
        JsonNode ledger = get("/internal/v1/ledger/accounts/{accountNumber}", accountNumber);
        return new AccountCheck(account.path("ownerId").asText(), account.path("status").asText(),
                ledger.path("currency").asText());
    }

    private JsonNode get(String path, String accountNumber) {
        JsonNode body;
        try {
            body = client.get().uri(path, accountNumber).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RequestRefusedException(HttpStatus.NOT_FOUND, "Account not found");
        } catch (RestClientException e) {
            // The exception message can carry the URL, and so the account number; only the type is logged.
            log.warn("account-service did not answer: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
        if (body == null) {
            throw unavailable();
        }
        return body;
    }

    private static RequestRefusedException unavailable() {
        return new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                "Accounts are unavailable right now. Try again shortly.");
    }
}
