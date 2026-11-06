package org.elibanking.walletservice.pin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The code from the email, the owner's password, and the new PIN. {@code toString} hides all three, so a logged request
 * cannot leak them (threat model T4).
 */
public record ChangePinRequest(
        @Pattern(regexp = "^\\d{6}$", message = "code is six digits") String code,
        @NotBlank String password,
        @Pattern(regexp = "^\\d{4}$", message = "PIN is four digits") String pin) {

    @Override
    public String toString() {
        return "ChangePinRequest[code=****, password=****, pin=****]";
    }
}
