package org.elibanking.transactionservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the recovery on a schedule: the holds of committed transfers (wallet-plan 3.8), and the transfers that were left
 * waiting for their code (wallet-plan 8.34). Tests turn it off and call the recovery themselves.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "transfer.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class TransferRecoveryJob {

    private final TransactionalService transfers;

    @Scheduled(fixedDelayString = "${transfer.recovery.delay-ms:60000}")
    public void run() {
        // Each step runs even if the other failed, so one failing step does not stop the other.
        try {
            transfers.holdStuckTransfers();
        } catch (RuntimeException e) {
            log.error("Hold recovery failed: {}", e.getMessage(), e);
        }
        try {
            transfers.expireAbandonedVerifications();
        } catch (RuntimeException e) {
            log.error("Abandoned-transfer recovery failed: {}", e.getMessage(), e);
        }
    }
}
