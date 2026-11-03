package org.elibanking.paymentservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Internal call to account-service (no user headers, so no ownership check on that side). */
@Component
public class AccountClient {

    private final RestClient restClient;

    public AccountClient(@Value("${account.service.url}") String accountServiceUrl) {
        this.restClient = RestClient.builder().baseUrl(accountServiceUrl).build();
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
