package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedPayoutProviderTest {

    @Test
    void accountDigitsKeepTheirDeterministicOutcomes() {
        SimulatedPayoutProvider provider = new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.SUCCESS, 0);

        assertEquals(PayoutProvider.SendStatus.FAILED, send(provider, "out-1", "0123456780").status());
        assertEquals(PayoutProvider.PayoutStatus.FAILED, provider.getStatus("out-1"));

        assertEquals(PayoutProvider.SendStatus.PENDING, send(provider, "out-2", "0123456781").status());
        assertEquals(PayoutProvider.PayoutStatus.SUCCESS, provider.getStatus("out-2"));

        send(provider, "out-3", "0123456782");
        assertEquals(PayoutProvider.PayoutStatus.SUCCESS, provider.getStatus("out-3"));
        assertEquals(PayoutProvider.PayoutStatus.REVERSED, provider.getStatus("out-3"));
    }

    @Test
    void configuredDefaultAppliesToOtherDigitsAndSendIsIdempotentByKey() {
        SimulatedPayoutProvider provider = new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.FAILURE, 0);
        PayoutProvider.SendResult first = send(provider, "out-4", "0123456783");
        PayoutProvider.SendResult replay = send(provider, "out-4", "0123456785");

        assertEquals(PayoutProvider.SendStatus.FAILED, first.status());
        assertEquals(first, replay);
        assertEquals(PayoutProvider.PayoutStatus.FAILED, provider.getStatus("out-4"));
        assertEquals(PayoutProvider.PayoutStatus.NOT_FOUND, provider.getStatus("out-unknown"));

        SimulatedPayoutProvider pending = new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.PENDING, 0);
        assertEquals(PayoutProvider.SendStatus.PENDING, send(pending, "out-5", "0123456783").status());
        assertEquals(PayoutProvider.PayoutStatus.PENDING, pending.getStatus("out-5"));

        SimulatedPayoutProvider success = new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.SUCCESS, 0);
        assertEquals(PayoutProvider.SendStatus.ACCEPTED, send(success, "out-6", "0123456783").status());
        assertEquals(PayoutProvider.PayoutStatus.SUCCESS, success.getStatus("out-6"));
    }

    @Test
    void concurrentSendsWithTheSameKeyShareOneProviderResult() {
        SimulatedPayoutProvider provider = new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.SUCCESS, 0);
        List<CompletableFuture<PayoutProvider.SendResult>> sends = IntStream.range(0, 50)
                .mapToObj(ignored -> CompletableFuture.supplyAsync(
                        () -> send(provider, "out-concurrent", "0123456783")))
                .toList();

        List<PayoutProvider.SendResult> results = sends.stream().map(CompletableFuture::join).toList();

        assertEquals(1, results.stream().distinct().count());
        assertEquals("SIM-out-concurrent", results.getFirst().providerRef());
    }

    @Test
    void configuredDelayAppliesToSendAndStatus() {
        SimulatedPayoutProvider provider = new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.SUCCESS, 30);

        long sendStart = System.nanoTime();
        send(provider, "out-delay", "0123456783");
        long sendTime = Duration.ofNanos(System.nanoTime() - sendStart).toMillis();
        long statusStart = System.nanoTime();
        provider.getStatus("out-delay");
        long statusTime = Duration.ofNanos(System.nanoTime() - statusStart).toMillis();

        assertTrue(sendTime >= 25, "send took " + sendTime + "ms");
        assertTrue(statusTime >= 25, "getStatus took " + statusTime + "ms");
    }

    @Test
    void rejectsNegativeSimulatedDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatedPayoutProvider(SimulatedPayoutProvider.DefaultOutcome.SUCCESS, -1));
    }

    @Test
    void providerSelectionAndDefaultOutcomeAreValidatedAtStartup() {
        PayoutProviderConfiguration configuration = new PayoutProviderConfiguration();
        StandardEnvironment env = new StandardEnvironment();
        String base = "https://api.flutterwave.com/v3";

        // Flutterwave selected but no secret key: fail closed.
        assertThrows(IllegalStateException.class, () -> configuration.payoutProvider("flutterwave", "success", 0,
                "", "whsec", base, env));
        // Flutterwave with a test key but no webhook secret: fail closed.
        assertThrows(IllegalStateException.class, () -> configuration.payoutProvider("flutterwave", "success", 0,
                "FLWSECK_TEST-abc", "", base, env));
        // An unsupported provider is rejected.
        assertThrows(IllegalStateException.class, () -> configuration.payoutProvider("paystack", "success", 0,
                "", "", base, env));
        // A bad simulator default outcome is rejected.
        assertThrows(IllegalArgumentException.class, () -> configuration.payoutProvider("simulator", "unknown", 0,
                "", "", base, env));
        // A valid test configuration builds the Flutterwave adapter without a network call.
        assertInstanceOf(FlutterwavePayoutProvider.class, configuration.payoutProvider("flutterwave", "success", 0,
                "FLWSECK_TEST-abc", "whsec_test", base, env));
    }

    private static PayoutProvider.SendResult send(SimulatedPayoutProvider provider, String key, String accountNumber) {
        return provider.send(key, new BigDecimal("100.00"), "001", accountNumber, "Test Account", "test");
    }
}
