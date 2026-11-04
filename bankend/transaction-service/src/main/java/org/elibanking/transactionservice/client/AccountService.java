package org.elibanking.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;


@FeignClient(name = "account-service", url = "${account.service.url}")
public interface AccountService {
    /** Internal call (no user headers) — account-service returns the account without an ownership check. */
    @GetMapping("/api/v1/accounts/{accountNumber}")
    AccountInfo getAccount(@PathVariable("accountNumber") String accountNumber);
}
