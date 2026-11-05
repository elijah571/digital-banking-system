package org.elibanking.walletservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * The PIN pepper and the PIN OTP key must each be set and at least 32 bytes, or wallet-service does not start (threat model
 * T12 and D-P7). The message names the setting and never shows its value.
 */
@Component
public class PinSecretsGuard {

    public static final int MIN_BYTES = 32;

    public PinSecretsGuard(@Value("${pin.pepper:}") String pepper,
                           @Value("${pin.otp-hmac-key:}") String otpHmacKey) {
        require(pepper, "PIN_PEPPER");
        require(otpHmacKey, "PIN_OTP_HMAC_KEY");
    }

    static void require(String value, String name) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
            throw new IllegalStateException(name + " must be set, and at least " + MIN_BYTES + " bytes long");
        }
    }
}
