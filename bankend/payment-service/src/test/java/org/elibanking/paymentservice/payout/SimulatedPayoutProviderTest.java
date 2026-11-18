package org.elibanking.paymentservice.payout;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void simulatorSelectionCreatesOnlyTheSimulatorProviderBean() {
        new ApplicationContextRunner().withUserConfiguration(PayoutProviderConfiguration.class)
                .withPropertyValues("payout.provider=simulator").run(context -> {
                    assertTrue(context.isRunning());
                    assertEquals(1, context.getBeansOfType(PayoutProvider.class).size());
                    assertEquals("simulator", context.getBean(PayoutProvider.class).getProviderName());
                    assertTrue(context.getBean(PayoutProvider.class) instanceof SimulatedPayoutProvider);
                });
    }

    @Test
    void monnifySelectionFailsFastWithTheMissingSourceAccountSetting() {
        new ApplicationContextRunner().withUserConfiguration(PayoutProviderConfiguration.class)
                .withPropertyValues("payout.provider=monnify", "payout.monnify.api-key=test-key",
                        "payout.monnify.secret-key=test-secret", "payout.monnify.source-account-number=")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(allMessages(context.getStartupFailure()).contains("MONNIFY_SOURCE_ACCOUNT_NUMBER"));
                });
    }

    @Test
    void flutterwaveSelectionRequiresItsSecretAndWebhookSettings() {
        new ApplicationContextRunner().withUserConfiguration(PayoutProviderConfiguration.class)
                .withPropertyValues("payout.provider=flutterwave", "payout.flutterwave.webhook-secret=test-webhook",
                        "payout.monnify.api-key=test-key", "payout.monnify.secret-key=test-secret",
                        "payout.monnify.source-account-number=0123456789")
                .run(context -> {
                    assertTrue(allMessages(context.getStartupFailure()).contains("FLUTTERWAVE_SECRET_KEY"));
                });

        new ApplicationContextRunner().withUserConfiguration(PayoutProviderConfiguration.class)
                .withPropertyValues("payout.provider=flutterwave", "payout.flutterwave.secret-key=FLWSECK_TEST-test",
                        "payout.monnify.api-key=test-key", "payout.monnify.secret-key=test-secret",
                        "payout.monnify.source-account-number=0123456789")
                .run(context -> {
                    assertTrue(allMessages(context.getStartupFailure()).contains("FLUTTERWAVE_WEBHOOK_SECRET"));
                });
    }

    @Test
    void unsupportedPayoutProviderFailsWithTheSelectedSettingName() {
        new ApplicationContextRunner().withUserConfiguration(PayoutProviderConfiguration.class)
                .withPropertyValues("payout.provider=paystack").run(context -> {
                    assertTrue(allMessages(context.getStartupFailure()).contains("Unsupported PAYOUT_PROVIDER"));
                });
    }

    @Test
    void invalidSimulatorOutcomeStillFailsDuringProviderCreation() {
        new ApplicationContextRunner().withUserConfiguration(PayoutProviderConfiguration.class)
                .withPropertyValues("payout.provider=simulator", "payout.sim.default-outcome=unknown")
                .run(context -> assertTrue(allMessages(context.getStartupFailure()).contains("PAYOUT_SIM_DEFAULT_OUTCOME")));
    }

    @Test
    void flutterwaveSelectionBuildsOneConfiguredRouterProvider() {
        new ApplicationContextRunner().withUserConfiguration(PayoutProviderConfiguration.class)
                .withPropertyValues("payout.provider=flutterwave", "payout.flutterwave.secret-key=FLWSECK_TEST-test",
                        "payout.flutterwave.webhook-secret=test-webhook", "payout.monnify.api-key=test-key",
                        "payout.monnify.secret-key=test-secret", "payout.monnify.source-account-number=0123456789")
                .run(context -> {
                    assertTrue(context.isRunning());
                    assertEquals(1, context.getBeansOfType(PayoutProvider.class).size());
                    assertTrue(context.getBean(PayoutProvider.class) instanceof ResilientPayoutRouter);
                });
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static PayoutProvider.SendResult send(SimulatedPayoutProvider provider, String key, String accountNumber) {
        return provider.send(key, new BigDecimal("100.00"), "001", accountNumber, "Test Account", "test");
    }
}
