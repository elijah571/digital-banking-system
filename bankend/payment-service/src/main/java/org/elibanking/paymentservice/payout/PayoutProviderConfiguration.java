package org.elibanking.paymentservice.payout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.client.RestClient;

/**
 * Selects the payout provider from {@code PAYOUT_PROVIDER} (wallet-plan 9.3). The code default is {@code simulator}, so tests and CI
 * never need provider credentials and never touch the network; the demo prefers {@code flutterwave}. Selection is deterministic: an
 * unsupported value fails startup, and a Flutterwave selection validates its credentials before any payout can be initiated.
 */
@Configuration
public class PayoutProviderConfiguration {

    @Bean
    PayoutProvider payoutProvider(
            @Value("${payout.provider:simulator}") String provider,
            @Value("${payout.sim.default-outcome:success}") String defaultOutcome,
            @Value("${payout.sim.delay-ms:0}") long delayMs,
            @Value("${payout.flutterwave.secret-key:}") String flutterwaveSecret,
            @Value("${payout.flutterwave.webhook-secret:}") String flutterwaveWebhookSecret,
            @Value("${payout.flutterwave.base-url:https://api.flutterwave.com/v3}") String flutterwaveBaseUrl,
            Environment environment) {
        if ("simulator".equalsIgnoreCase(provider)) {
            return new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.parse(defaultOutcome), delayMs);
        }
        if (!"flutterwave".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("Unsupported PAYOUT_PROVIDER '" + provider + "' (use flutterwave or simulator)");
        }
        boolean testKey = flutterwaveSecret.contains("_TEST");
        boolean liveKey = flutterwaveSecret.startsWith("FLWSECK") && !testKey;
        if (!testKey && !liveKey) {
            throw new IllegalStateException("FLUTTERWAVE_SECRET_KEY must be configured when PAYOUT_PROVIDER=flutterwave");
        }
        if (flutterwaveWebhookSecret == null || flutterwaveWebhookSecret.isBlank()) {
            throw new IllegalStateException("FLUTTERWAVE_WEBHOOK_SECRET must be configured when PAYOUT_PROVIDER=flutterwave");
        }
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production && !liveKey) {
            throw new IllegalStateException("prod profile requires a Flutterwave live secret key");
        }
        if (!production && !testKey) {
            throw new IllegalStateException("dev and test profiles require a Flutterwave test secret key (FLWSECK_TEST-...)");
        }
        RestClient client = flutterwaveClient(RestClient.builder(), flutterwaveSecret, flutterwaveBaseUrl);
        return new FlutterwavePayoutProvider(client);
    }

    static RestClient flutterwaveClient(RestClient.Builder builder, String flutterwaveSecret, String flutterwaveBaseUrl) {
        return builder.baseUrl(flutterwaveBaseUrl)
                .defaultHeader("Authorization", "Bearer " + flutterwaveSecret)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "EliBanking-Payment-Service/1.0")
                .build();
    }
}
