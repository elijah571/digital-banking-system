package org.elibanking.paymentservice.client;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.common.web.RequestIdContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Internal call to account-service. Each request carries a SERVICE token (wallet-plan 3.7), so there are no user headers
 * and no ownership check on that side.
 */
@Component
public class AccountClient {

    private final RestClient restClient;

    @Autowired
    public AccountClient(@Value("${account.service.url}") String accountServiceUrl, ServiceTokens accountServiceTokens) {
        this(RestClient.builder(), accountServiceUrl, accountServiceTokens);
    }

    /** The builder is a parameter so a test can bind a mock server to it. */
    AccountClient(RestClient.Builder builder, String accountServiceUrl, ServiceTokens accountServiceTokens) {
        this.restClient = builder.clone()
                .baseUrl(accountServiceUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(accountServiceTokens.token());
                    RequestIdContext.apply(request.getHeaders());
                    return execution.execute(request, body);
                })
                .build();
    }

    /** @throws org.springframework.web.client.HttpClientErrorException.NotFound if the account does not exist */
    public AccountInfo getAccount(String accountNumber) {
        return restClient.get()
                .uri("/api/v1/accounts/{accountNumber}", accountNumber)
                .retrieve()
                .body(AccountInfo.class);
    }

    public record AccountInfo(String accountNumber, String ownerId, String status) {
    }
}
