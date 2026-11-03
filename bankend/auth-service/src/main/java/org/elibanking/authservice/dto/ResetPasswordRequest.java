package org.elibanking.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @Schema(description = "Token from the password-reset email") @NotBlank String token,

        @Schema(example = "NewSecret456")
        @NotBlank @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        @Pattern(regexp = PasswordRules.REGEX, message = PasswordRules.MESSAGE)
        String newPassword
) {
}
