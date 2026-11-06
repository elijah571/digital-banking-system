package org.elibanking.walletservice.deposit;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Expires a card deposit that was not paid in time (wallet-plan 3.5, step 6). The intent is cancelled at Stripe first, and the
 * deposit becomes EXPIRED only once Stripe confirms the cancel. So a success that arrives late cannot land on an expired deposit:
 * Stripe cannot succeed an intent it has cancelled. A deposit with no intent recorded has nothing to cancel.
 */
@Slf4j
@Service
public class DepositExpiryService {

    static final int BATCH = 50;

    private final DepositRepository deposits;
    private final PaymentServiceClient payments;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public DepositExpiryService(DepositRepository deposits, PaymentServiceClient payments, TransactionTemplate tx,
                                JdbcTemplate jdbc) {
        this.deposits = deposits;
        this.payments = payments;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    /** Expires the due deposits, oldest first, one batch at a time. Returns how many expired. */
    public int expireDue() {
        List<String> due = deposits.findIdsExpiredBy(DepositStatus.PENDING, Instant.now(), PageRequest.of(0, BATCH));
        int expired = 0;
        for (String id : due) {
            if (expire(id)) {
                expired++;
            }
        }
        return expired;
    }

    /** Expires one deposit, if it is still PENDING and Stripe confirms the cancel of its intent. Returns true if it expired. */
    public boolean expire(String depositId) {
        Deposit deposit = deposits.findById(depositId).orElse(null);
        if (deposit == null || deposit.getStatus() != DepositStatus.PENDING) {
            return false;
        }
        String intentId = deposit.getPaymentIntentId();
        if (intentId != null) {
            try {
                if (!payments.cancelIntent(intentId)) {
                    log.info("Deposit {} is not expired: Stripe will not cancel its intent, so its outcome will arrive", depositId);
                    return false;
                }
            } catch (RequestRefusedException unreachable) {
                log.warn("Deposit {} is not expired: payment-service did not answer, so it is tried again", depositId);
                return false;
            }
        }
        Boolean expired = tx.execute(status -> {
            Deposit fresh = deposits.findById(depositId).orElseThrow();
            if (fresh.getStatus() != DepositStatus.PENDING) {
                // A webhook completed the deposit while the cancel ran. It is not expired.
                return false;
            }
            fresh.setStatus(DepositStatus.EXPIRED);
            jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details)"
                            + " VALUES ('wallet-service', 'DEPOSIT_EXPIRED', 'deposit', ?, JSON_OBJECT('detail', ?))",
                    depositId, intentId == null ? "no intent was recorded" : "intent cancelled at Stripe");
            return true;
        });
        return Boolean.TRUE.equals(expired);
    }
}
