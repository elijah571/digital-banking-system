package org.elibanking.walletservice.deposit;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** payment-service's outcome for a card deposit (wallet-plan 3.5, 3.8). A failure is rethrown, so the record is retried (DepositEventConfig). */
@Component
public class DepositEventConsumer {

    private final DepositSettlementService settlement;

    public DepositEventConsumer(DepositSettlementService settlement) {
        this.settlement = settlement;
    }

    @KafkaListener(topics = {DepositSettlementService.SUCCEEDED, DepositSettlementService.FAILED},
            groupId = "wallet-service-group")
    public void onStripeOutcome(String message) {
        settlement.apply(message);
    }
}
