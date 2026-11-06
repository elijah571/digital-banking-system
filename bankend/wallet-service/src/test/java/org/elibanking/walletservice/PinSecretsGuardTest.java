package org.elibanking.walletservice;

import org.elibanking.walletservice.config.PinSecretsGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wallet-service does not start without its PIN secrets, each at least 32 bytes (threat model T12, D-P7). Checked with a context
 * of just the guard, so no database is needed.
 */
class PinSecretsGuardTest {

    private static final String PEPPER = "a-pepper-of-at-least-thirty-two-bytes-long";
    private static final String OTP_KEY = "an-otp-key-of-at-least-thirty-two-bytes-long";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PinSecretsGuard.class);

    @Test
    void startsWhenBothSecretsAreSetAndLongEnough() {
        runner.withPropertyValues("pin.pepper=" + PEPPER, "pin.otp-hmac-key=" + OTP_KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithoutAPepper() {
        runner.withPropertyValues("pin.otp-hmac-key=" + OTP_KEY).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("PIN_PEPPER must be set");
        });
    }

    @Test
    void refusesAShortOtpKeyWithoutShowingIt() {
        runner.withPropertyValues("pin.pepper=" + PEPPER, "pin.otp-hmac-key=too-short")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("PIN_OTP_HMAC_KEY must be set");
                    assertThat(context.getStartupFailure()).satisfies(failure ->
                            assertThat(failure.getMessage()).doesNotContain("too-short"));
                });
    }
}
