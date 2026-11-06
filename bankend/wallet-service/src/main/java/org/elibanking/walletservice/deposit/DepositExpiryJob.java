package org.elibanking.walletservice.deposit;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the deposit expiry on a schedule (wallet-plan 3.5, step 6). The switch is read on each run, not when the bean is created,
 * so a test context that turns it off never calls Stripe or the ledger. Tests call the service themselves.
 */
@Component
public class DepositExpiryJob {

    private final DepositExpiryService expiry;
    private final Environment environment;

    public DepositExpiryJob(DepositExpiryService expiry, Environment environment) {
        this.expiry = expiry;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${wallet.deposits.expiry-job.delay-ms:60000}")
    public void run() {
        if (environment.getProperty("wallet.deposits.expiry-job.enabled", Boolean.class, true)) {
            expiry.expireDue();
        }
    }
}
