package org.elibanking.paymentservice.payout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PayoutProviderConfiguration {

    @Bean
    PayoutProvider payoutProvider(
            @Value("${payout.provider:simulator}") String provider,
            @Value("${payout.sim.default-outcome:success}") String defaultOutcome,
            @Value("${payout.sim.delay-ms:0}") long delayMs) {
        if (!"simulator".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("Unsupported PAYOUT_PROVIDER '" + provider + "'; phase 6 supports simulator only");
        }
        return new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.parse(defaultOutcome), delayMs);
    }
}
