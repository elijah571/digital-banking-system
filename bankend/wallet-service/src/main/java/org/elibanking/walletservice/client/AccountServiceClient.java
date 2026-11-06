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

import java.util.List;

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

    /**
     * One line of a ledger entry. Name the account by {@code accountNumber} (a customer wallet) or by {@code systemAccount} (a
     * system account code). Exactly one of the two. {@code side} is DEBIT or CREDIT.
     */
    public record LedgerLine(String accountNumber, String systemAccount, String side, String amount, String currency) {
    }

    /** What account-service answered. {@code replayed} is true when the key had been posted already and nothing new was written. */
    public record PostResult(long entryId, boolean replayed) {
    }

    /** The body account-service's internal posting API takes (LedgerApi.PostEntryRequest). */
    private record PostEntryBody(String type, String idempotencyKey, String reference, String correlationId, String createdBy,
                                 List<LedgerLine> lines) {
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

    /**
     * Posts a ledger entry through account-service's internal API (wallet-plan 3.2). The key is permanent, so a repeat of the same
     * entry is a replay and moves no money. Any failure is refused with 503, and the caller retries.
     */
    public PostResult postEntry(String type, String idempotencyKey, String reference, String correlationId,
                                List<LedgerLine> lines) {
        JsonNode body;
        try {
            body = client.post()
                    .uri("/internal/v1/ledger/entries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PostEntryBody(type, idempotencyKey, reference, correlationId, "wallet-service", lines))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("account-service did not post ledger entry {}: {}", idempotencyKey, e.getClass().getSimpleName());
            throw unavailable();
        }
        if (body == null || !body.hasNonNull("entryId")) {
            throw unavailable();
        }
        return new PostResult(body.path("entryId").asLong(), body.path("replayed").asBoolean());
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
