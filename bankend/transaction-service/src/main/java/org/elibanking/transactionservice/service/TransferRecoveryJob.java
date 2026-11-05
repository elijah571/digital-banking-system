package org.elibanking.transactionservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the hold recovery on a schedule (wallet-plan 3.8). Tests turn it off and call the recovery themselves. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "transfer.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class TransferRecoveryJob {

    private final TransactionalService transfers;

    @Scheduled(fixedDelayString = "${transfer.recovery.delay-ms:60000}")
    public void run() {
        transfers.holdStuckTransfers();
    }
}
