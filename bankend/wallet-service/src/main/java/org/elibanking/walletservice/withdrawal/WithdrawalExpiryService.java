package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.payout.OutboundPayoutRepository;
import org.elibanking.walletservice.payout.PayoutState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Expires the payouts that no admin decided in time (wallet-plan 8.3). Each is expired on its own, so a decision made while the batch runs
 * is not undone: {@link WithdrawalService#expire} checks the state again.
 */
@Service
public class WithdrawalExpiryService {

    static final int BATCH = 50;

    private final OutboundPayoutRepository payouts;
    private final WithdrawalService withdrawals;
    private final Duration expiry;

    public WithdrawalExpiryService(OutboundPayoutRepository payouts, WithdrawalService withdrawals,
                                   @Value("${wallet.withdrawals.approval-expiry}") Duration expiry) {
        this.payouts = payouts;
        this.withdrawals = withdrawals;
        this.expiry = expiry;
    }

    /** Expires the due payouts, oldest first, one batch at a time. Returns how many expired. */
    public int expireDue() {
        List<Long> due = payouts.findIdsInStateSince(PayoutState.PENDING_APPROVAL, Instant.now().minus(expiry),
                PageRequest.of(0, BATCH));
        int expired = 0;
        for (long id : due) {
            if (withdrawals.expire(id)) {
                expired++;
            }
        }
        return expired;
    }
}
