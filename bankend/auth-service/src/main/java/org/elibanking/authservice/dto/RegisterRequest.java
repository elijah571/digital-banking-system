package org.elibanking.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Schema(example = "Alice Johnson")
        @NotBlank(message = "Full name is required") @Size(max = 100)
        String fullName,

        @Schema(example = "alice@example.com")
        @NotBlank(message = "Email is required") @Email(message = "Invalid email format")
        String email,

        @Schema(description = "8-72 characters, at least one letter and one digit", example = "Secret123")
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        @Pattern(regexp = PasswordRules.REGEX, message = PasswordRules.MESSAGE)
        String password
) {
}
