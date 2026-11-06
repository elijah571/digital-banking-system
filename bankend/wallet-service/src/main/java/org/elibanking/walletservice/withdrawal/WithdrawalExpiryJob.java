package org.elibanking.walletservice.withdrawal;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the withdrawal expiry on a schedule (wallet-plan 8.3). The switch is read on each run, not when the bean is created, so a test
 * context that turns it off never releases money on its own. Tests call the service themselves.
 */
@Component
public class WithdrawalExpiryJob {

    private final WithdrawalExpiryService expiry;
    private final Environment environment;

    public WithdrawalExpiryJob(WithdrawalExpiryService expiry, Environment environment) {
        this.expiry = expiry;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${wallet.withdrawals.expiry-job.delay-ms:60000}")
    public void run() {
        if (environment.getProperty("wallet.withdrawals.expiry-job.enabled", Boolean.class, true)) {
            expiry.expireDue();
        }
    }
}
