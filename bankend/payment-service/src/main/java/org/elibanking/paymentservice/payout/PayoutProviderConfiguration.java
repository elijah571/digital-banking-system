package org.elibanking.paymentservice.payout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.client.RestClient;

@Configuration
public class PayoutProviderConfiguration {

    @Bean
    PayoutProvider payoutProvider(
            @Value("${payout.provider:simulator}") String provider,
            @Value("${payout.sim.default-outcome:success}") String defaultOutcome,
            @Value("${payout.sim.delay-ms:0}") long delayMs,
            @Value("${payout.paystack.secret-key:}") String paystackSecret,
            @Value("${payout.paystack.base-url:https://api.paystack.co}") String paystackBaseUrl,
            Environment environment,
            PaystackRecipientRepository recipients) {
        if ("simulator".equalsIgnoreCase(provider)) {
            return new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.parse(defaultOutcome), delayMs);
        }
        if (!"paystack".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("Unsupported PAYOUT_PROVIDER '" + provider + "'");
        }
        boolean testKey = paystackSecret.startsWith("sk_test_");
        boolean liveKey = paystackSecret.startsWith("sk_live_");
        if (!testKey && !liveKey) {
            throw new IllegalStateException("PAYSTACK_SECRET_KEY must be configured when PAYOUT_PROVIDER=paystack");
        }
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production && !liveKey) {
            throw new IllegalStateException("prod profile requires a Paystack live secret key");
        }
        if (!production && !testKey) {
            throw new IllegalStateException("dev and test profiles require a Paystack test secret key");
        }
        RestClient client = paystackClient(RestClient.builder(), paystackSecret, paystackBaseUrl);
        return new PaystackPayoutProvider(client, recipients);
    }

    static RestClient paystackClient(RestClient.Builder builder, String paystackSecret, String paystackBaseUrl) {
        return builder.baseUrl(paystackBaseUrl)
                .defaultHeader("Authorization", "Bearer " + paystackSecret)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "EliBanking-Payment-Service/1.0")
                .build();
    }
}
