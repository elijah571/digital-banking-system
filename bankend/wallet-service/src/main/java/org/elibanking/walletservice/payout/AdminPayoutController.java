package org.elibanking.walletservice.payout;

import org.elibanking.walletservice.withdrawal.WithdrawalResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Maker-checker queue for operations staff; the API security chain requires ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/payouts")
public class AdminPayoutController {

    private final OutboundPayoutRepository payouts;

    public AdminPayoutController(OutboundPayoutRepository payouts) {
        this.payouts = payouts;
    }

    @GetMapping
    public List<WithdrawalResponse> list(@RequestParam(defaultValue = "PENDING_APPROVAL") PayoutState state,
                                         @RequestParam(defaultValue = "100") int limit) {
        return payouts.findAllByStateOrderByCreatedAtDesc(state, PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(p -> new WithdrawalResponse(p.getId(), p.getState().name(), p.getAccountNumber(),
                        p.getAmount().toPlainString(), p.getFee().toPlainString(), p.getCurrency(), p.getBankCode(),
                        p.getDestinationAccountNumber(), p.getDestinationAccountName(), p.getFailureReason()))
                .toList();
    }
}
