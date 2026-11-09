package org.elibanking.paymentservice.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.elibanking.paymentservice.payout.PayoutProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** The bank list and the name enquiry for wallet-service (wallet-plan 8.4, 8.7). Only wallet-service may call them, see SecurityConfig. */
@RestController
@RequestMapping("/internal/v1")
public class InternalPayoutController {

    /** A name enquiry. The account number is ten digits, as the Nigerian banks use. */
    public record ResolveRequest(@NotBlank @Size(max = 20) String bankCode,
                                 @NotBlank @Pattern(regexp = "^\\d{10}$", message = "accountNumber must be 10 digits") String accountNumber) {
    }

    /** The outcome (FOUND, NOT_FOUND or UNAVAILABLE), and the holder's name when it was found. */
    public record ResolveResponse(String status, String accountName) {
    }

    private final PayoutProvider provider;

    public InternalPayoutController(PayoutProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/banks")
    public List<PayoutProvider.Bank> banks() {
        return provider.listBanks();
    }

    @PostMapping("/accounts/resolve")
    public ResolveResponse resolve(@Valid @RequestBody ResolveRequest request) {
        PayoutProvider.Resolution resolution = provider.resolveAccount(request.bankCode(), request.accountNumber());
        return new ResolveResponse(resolution.outcome().name(), resolution.accountName());
    }

    /** A payout to send: its key (out-{id}), the amount in naira with two decimals, and the destination (wallet-plan 8.3, 8.7). */
    public record SendRequest(@NotBlank @Pattern(regexp = "^out-\\d+$", message = "key must be out-{id}") String key,
                              @NotBlank @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "amount must be a number with two decimals") String amount,
                              @NotBlank @Size(max = 20) String bankCode,
                              @NotBlank @Pattern(regexp = "^\\d{10}$", message = "accountNumber must be 10 digits") String accountNumber,
                              @Size(max = 200) String accountName,
                              @Size(max = 255) String narration) {
    }

    /** The provider's answer to a send: its reference, and ACCEPTED, PENDING or FAILED. */
    public record SendResponse(String providerRef, String status) {
    }

    /** The provider's status and transfer facts; wallet checks these against its payout before moving ledger money. */
    public record StatusResponse(String status, String reference, String amount, String currency) {
    }

    @PostMapping("/payouts")
    public SendResponse send(@Valid @RequestBody SendRequest request) {
        PayoutProvider.SendResult result = provider.send(request.key(), new BigDecimal(request.amount()), request.bankCode(),
                request.accountNumber(), request.accountName(), request.narration());
        return new SendResponse(result.providerRef(), result.status().name());
    }

    @GetMapping("/payouts/{key}")
    public StatusResponse status(@PathVariable String key) {
        if (!key.matches("out-\\d+")) {
            throw new IllegalArgumentException("key must be out-{id}");
        }
        PayoutProvider.StatusResult result = provider.getStatusDetails(key);
        return new StatusResponse(result.status().name(), result.reference(),
                result.amount() == null ? null : result.amount().toPlainString(), result.currency());
    }
}
