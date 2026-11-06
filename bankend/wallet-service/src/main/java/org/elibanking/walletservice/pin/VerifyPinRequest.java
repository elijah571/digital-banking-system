package org.elibanking.walletservice.pin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The user whose PIN is checked, and the PIN. {@code toString} hides the PIN, so a logged request cannot leak it (threat
 * model T4).
 */
public record VerifyPinRequest(
        @NotBlank String userId,
        @Pattern(regexp = "^\\d{4}$", message = "PIN is four digits") String pin) {

    @Override
    public String toString() {
        return "VerifyPinRequest[userId=" + userId + ", pin=****]";
    }
}
