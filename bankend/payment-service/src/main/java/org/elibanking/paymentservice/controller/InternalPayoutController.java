package org.elibanking.paymentservice.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.elibanking.paymentservice.payout.PayoutProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
