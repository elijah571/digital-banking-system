package org.elibanking.transactionservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Under the {@code prod} profile, transaction-service does not start while {@code TRANSFER_PIN_REQUIRED} is off (wallet-plan 8.22).
 * Phases 3 and 4 ship together, so this is the guard that keeps a phase 3 build out of prod on its own.
 */
@Component
@Profile("prod")
public class PinRequiredStartupCheck {

    public PinRequiredStartupCheck(@Value("${transfer.pin-required:false}") boolean required) {
        if (!required) {
            throw new IllegalStateException("TRANSFER_PIN_REQUIRED must be true under the prod profile (wallet-plan 8.22)");
        }
    }
}
