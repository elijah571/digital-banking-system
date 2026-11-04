package org.elibanking.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * The internal ledger API of account-service (wallet-plan 3.2, 3.4). A transfer holds its amount in TRANSFER_IN_FLIGHT, settles
 * it to the receiver, or releases it back to the sender. Amounts are money strings. AccountServiceAuth adds the service token.
 */
@FeignClient(name = "account-ledger", url = "${account.service.url}")
public interface LedgerClient {

    @PostMapping("/internal/v1/ledger/entries")
    LedgerEntryResponse post(@RequestBody LedgerEntryRequest request);

    record LedgerEntryRequest(String type, String idempotencyKey, String reference, String correlationId,
                              String createdBy, List<LedgerLine> lines) {
    }

    /** A line names a wallet by account number, or a system account by code: exactly one of the two. */
    record LedgerLine(String accountNumber, String systemAccount, String side, String amount, String currency) {
    }

    record LedgerEntryResponse(long entryId, boolean replayed) {
    }
}
