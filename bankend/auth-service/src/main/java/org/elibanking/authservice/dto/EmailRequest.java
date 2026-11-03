package org.elibanking.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailRequest(@Schema(example = "alice@example.com") @NotBlank @Email String email) {
}
