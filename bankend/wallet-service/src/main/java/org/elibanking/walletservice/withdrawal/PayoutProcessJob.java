package org.elibanking.walletservice.withdrawal;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the payout processor on a schedule (wallet-plan 8.3). The switch is read on each run, not when the bean is created, so a test context
 * that turns it off never sends money on its own. Tests call the processor themselves.
 */
@Component
public class PayoutProcessJob {

    private final PayoutProcessor processor;
    private final Environment environment;

    public PayoutProcessJob(PayoutProcessor processor, Environment environment) {
        this.processor = processor;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${wallet.payouts.process-job.delay-ms:10000}")
    public void run() {
        if (environment.getProperty("wallet.payouts.process-job.enabled", Boolean.class, true)) {
            processor.processDue();
        }
    }
}
