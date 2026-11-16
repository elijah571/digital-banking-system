package org.elibanking.walletservice.payout;

import org.elibanking.walletservice.withdrawal.WithdrawalResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.elibanking.walletservice.security.Caller;
import org.elibanking.walletservice.withdrawal.WithdrawalService;

import java.util.List;

/** Maker-checker queue for operations staff; the API security chain requires ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/payouts")
public class AdminPayoutController {

    public record AdminPayout(long payoutId, String state, String accountNumber, String amount, String currency,
                              String bankCode, String destinationAccountNumber, String destinationAccountName,
                              String activeProvider, int retryCount, String failureReason) { }
    public record CancelRequest(@NotBlank @Size(max = 500) String reason) { }

    private final OutboundPayoutRepository payouts;
    private final WithdrawalService withdrawals;

    public AdminPayoutController(OutboundPayoutRepository payouts, WithdrawalService withdrawals) {
        this.payouts = payouts;
        this.withdrawals = withdrawals;
    }

    @GetMapping
    public List<AdminPayout> list(@RequestParam(defaultValue = "PENDING_APPROVAL") PayoutState state,
                                         @RequestParam(defaultValue = "100") int limit) {
        return payouts.findAllByStateOrderByCreatedAtDesc(state, PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(p -> new AdminPayout(p.getId(), p.getState().name(), p.getAccountNumber(),
                        p.getAmount().toPlainString(), p.getCurrency(), p.getBankCode(),
                        p.getDestinationAccountNumber(), p.getDestinationAccountName(), p.getActiveProvider(),
                        p.getRetryCount(), p.getFailureReason()))
                .toList();
    }

    @PostMapping("/{id}/reroute")
    public void reroute(@PathVariable long id, @RequestParam String provider, Caller caller) {
        withdrawals.rerouteForOperations(caller, id, provider);
    }

    @PostMapping("/{id}/cancel-and-refund")
    public void cancelAndRefund(@PathVariable long id, @Valid @RequestBody CancelRequest body, Caller caller) {
        withdrawals.cancelForOperations(caller, id, body.reason());
    }
}
