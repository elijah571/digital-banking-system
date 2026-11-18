package org.elibanking.paymentservice.payout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.client.RestClient;

/** Creates only the payout route selected by {@code PAYOUT_PROVIDER}. */
@Configuration
public class PayoutProviderConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "payout.provider", havingValue = "simulator", matchIfMissing = true)
    PayoutProvider simulatorPayoutProvider(
            @Value("${payout.sim.default-outcome:success}") String defaultOutcome,
            @Value("${payout.sim.delay-ms:0}") long delayMs) {
        return new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.parse(defaultOutcome), delayMs);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "payout.provider", havingValue = "flutterwave")
    PayoutProvider flutterwavePayoutProvider(
            @Value("${payout.flutterwave.secret-key:}") String flutterwaveSecret,
            @Value("${payout.flutterwave.webhook-secret:}") String flutterwaveWebhookSecret,
            @Value("${payout.flutterwave.base-url:https://api.flutterwave.com/v3}") String flutterwaveBaseUrl,
            MonnifyTokenManager monnifyTokens,
            @Value("${payout.monnify.base-url:https://sandbox.monnify.com}") String monnifyBaseUrl,
            @Value("${payout.monnify.source-account-number:}") String monnifySourceAccountNumber,
            Environment environment) {
        validateFlutterwave(flutterwaveSecret, flutterwaveWebhookSecret, environment);
        validateMonnifyEnvironment(monnifyBaseUrl, environment);
        RestClient flutterwaveClient = flutterwaveClient(RestClient.builder(), flutterwaveSecret, flutterwaveBaseUrl);
        MonnifyPayoutProvider monnify = monnifyProvider(monnifyBaseUrl, monnifySourceAccountNumber, monnifyTokens);
        return new ResilientPayoutRouter(new FlutterwavePayoutProvider(flutterwaveClient), monnify);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "payout.provider", havingValue = "monnify")
    PayoutProvider monnifyPayoutProvider(
            @Value("${payout.monnify.base-url:https://sandbox.monnify.com}") String baseUrl,
            @Value("${payout.monnify.source-account-number:}") String sourceAccountNumber,
            MonnifyTokenManager tokens,
            Environment environment) {
        validateMonnifyEnvironment(baseUrl, environment);
        return monnifyProvider(baseUrl, sourceAccountNumber, tokens);
    }

    @Bean
    @ConditionalOnExpression("'${payout.provider:simulator}' != 'simulator' and '${payout.provider:simulator}' != 'flutterwave' and '${payout.provider:simulator}' != 'monnify'")
    PayoutProvider unsupportedPayoutProvider(@Value("${payout.provider}") String provider) {
        throw new IllegalStateException("Unsupported PAYOUT_PROVIDER '" + provider + "' (use simulator, flutterwave or monnify)");
    }

    @Bean
    @ConditionalOnExpression("'${payout.provider:simulator}' == 'flutterwave' or '${payout.provider:simulator}' == 'monnify'")
    MonnifyTokenManager monnifyTokenManager(
            @Value("${payout.monnify.base-url:https://sandbox.monnify.com}") String baseUrl,
            @Value("${payout.monnify.api-key:}") String apiKey,
            @Value("${payout.monnify.secret-key:}") String secretKey) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).defaultHeader("Accept", "application/json").build();
        return new MonnifyTokenManager(client, apiKey, secretKey);
    }

    @Bean
    @ConditionalOnProperty(name = "payout.provider", havingValue = "flutterwave")
    MerchantDirectory flutterwaveMerchantDirectory(
            @Value("${payout.flutterwave.secret-key:}") String flutterwaveSecret,
            @Value("${payout.flutterwave.base-url:https://api.flutterwave.com/v3}") String flutterwaveBaseUrl) {
        return new FlutterwaveMerchantDirectory(flutterwaveClient(RestClient.builder(), flutterwaveSecret, flutterwaveBaseUrl));
    }

    static RestClient flutterwaveClient(RestClient.Builder builder, String flutterwaveSecret, String flutterwaveBaseUrl) {
        return builder.baseUrl(flutterwaveBaseUrl)
                .defaultHeader("Authorization", "Bearer " + flutterwaveSecret)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "EliBanking-Payment-Service/1.0")
                .build();
    }

    private static MonnifyPayoutProvider monnifyProvider(String baseUrl, String sourceAccountNumber,
                                                          MonnifyTokenManager tokens) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "EliBanking-Payment-Service/1.0").build();
        return new MonnifyPayoutProvider(client, tokens, sourceAccountNumber);
    }

    private static void validateFlutterwave(String secret, String webhookSecret, Environment environment) {
        boolean testKey = secret.contains("_TEST");
        boolean liveKey = secret.startsWith("FLWSECK") && !testKey;
        if (!testKey && !liveKey) {
            throw new IllegalStateException("FLUTTERWAVE_SECRET_KEY must be configured when PAYOUT_PROVIDER=flutterwave");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("FLUTTERWAVE_WEBHOOK_SECRET must be configured when PAYOUT_PROVIDER=flutterwave");
        }
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production && !liveKey) {
            throw new IllegalStateException("prod profile requires a Flutterwave live secret key");
        }
        if (!production && !testKey) {
            throw new IllegalStateException("dev and test profiles require a Flutterwave test secret key (FLWSECK_TEST-...)");
        }
    }

    private static void validateMonnifyEnvironment(String baseUrl, Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        boolean sandbox = baseUrl != null && baseUrl.contains("sandbox.monnify.com");
        if (production && sandbox) {
            throw new IllegalStateException("prod profile requires MONNIFY_BASE_URL to use the live Monnify endpoint");
        }
        if (!production && !sandbox) {
            throw new IllegalStateException("dev and test profiles require MONNIFY_BASE_URL to use the Monnify sandbox");
        }
    }
}
