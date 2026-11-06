package org.elibanking.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The user whose password is checked, and the password. {@code toString} hides the password, so a logged request cannot
 * leak it (threat model T4).
 */
public record VerifyPasswordRequest(@NotBlank String userId, @NotBlank String password) {

    @Override
    public String toString() {
        return "VerifyPasswordRequest[userId=" + userId + ", password=****]";
    }
}
