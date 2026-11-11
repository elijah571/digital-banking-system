package org.elibanking.walletservice.client;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.common.security.ServiceTokens;
import org.elibanking.common.web.RequestIdContext;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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

    /** The holder's name and the account's status, for a name enquiry on our own bank (wallet-plan 8.4). */
    public record AccountHolder(String name, String status) {
    }

    public AccountHolder holder(String accountNumber) {
        JsonNode account = get("/api/v1/accounts/{accountNumber}", accountNumber);
        return new AccountHolder(account.path("accountHolderName").asText(), account.path("status").asText());
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

    private record ReceiptReviewBody(String source, String providerReference, String depositId, String reason, String currency,
                                     String amount, JsonNode payload) {
    }

    private final RestClient client;
    private final ObjectMapper mapper;

    public AccountServiceClient(@Value("${account-service.url}") String baseUrl,
                                @Qualifier("accountServiceTokens") ServiceTokens tokens, ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    RequestIdContext.apply(request.getHeaders());
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
        } catch (HttpClientErrorException.UnprocessableEntity refused) {
            // The ledger refused the entry, for a reason it names (insufficient funds, an inactive account). The caller decides what that means.
            throw new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, ledgerDetail(refused));
        } catch (RestClientException e) {
            log.warn("account-service did not post ledger entry {}: {}", idempotencyKey, e.getClass().getSimpleName());
            throw unavailable();
        }
        if (body == null || !body.hasNonNull("entryId")) {
            throw unavailable();
        }
        return new PostResult(body.path("entryId").asLong(), body.path("replayed").asBoolean());
    }

    /** Stores a provider receipt that cannot be credited and raises the ledger review alert. */
    public boolean quarantineReceipt(String source, String providerReference, String depositId, String reason,
                                     String currency, String amount, String payload) {
        JsonNode body;
        try {
            body = client.post()
                    .uri("/internal/v1/ledger/receipts/quarantine")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReceiptReviewBody(source, providerReference, depositId, reason, currency, amount,
                            mapper.readTree(payload)))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("account-service did not quarantine receipt: {}", e.getClass().getSimpleName());
            throw unavailable();
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("receipt payload is not valid JSON", e);
        }
        if (body == null || !body.hasNonNull("recorded")) {
            throw unavailable();
        }
        return body.path("recorded").asBoolean();
    }

    /** The account's daily transaction limit, as account-service holds it (wallet-plan D13). */
    public BigDecimal dailyLimit(String accountNumber) {
        JsonNode account = get("/api/v1/accounts/{accountNumber}", accountNumber);
        if (!account.hasNonNull("dailyTransactionLimit")) {
            throw unavailable();
        }
        return new BigDecimal(account.get("dailyTransactionLimit").asText());
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

    /** The reason the ledger gave in its problem response, or a plain line when it gave none. */
    private String ledgerDetail(HttpClientErrorException refused) {
        try {
            String detail = mapper.readTree(refused.getResponseBodyAsString()).path("detail").asText("");
            return detail.isEmpty() ? "The ledger refused the entry" : detail;
        } catch (RuntimeException unreadable) {
            return "The ledger refused the entry";
        }
    }

    private static RequestRefusedException unavailable() {
        return new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                "Accounts are unavailable right now. Try again shortly.");
    }
}
