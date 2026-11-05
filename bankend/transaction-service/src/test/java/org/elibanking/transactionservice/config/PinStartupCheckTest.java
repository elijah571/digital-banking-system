package org.elibanking.transactionservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Under the prod profile the context refuses to start while TRANSFER_PIN_REQUIRED is off (wallet-plan 8.22). Checked with a
 * context of just this component, so no database is needed.
 */
class PinStartupCheckTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PinRequiredStartupCheck.class);

    @Test
    void prodWithThePinRequirementOffDoesNotStart() {
        runner.withPropertyValues("spring.profiles.active=prod", "transfer.pin-required=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("TRANSFER_PIN_REQUIRED must be true");
        });
    }

    @Test
    void prodWithThePinRequirementOnStarts() {
        runner.withPropertyValues("spring.profiles.active=prod", "transfer.pin-required=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PinRequiredStartupCheck.class);
        });
    }

    @Test
    void devWithThePinRequirementOffStarts() {
        runner.withPropertyValues("transfer.pin-required=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PinRequiredStartupCheck.class);
        });
    }
}
