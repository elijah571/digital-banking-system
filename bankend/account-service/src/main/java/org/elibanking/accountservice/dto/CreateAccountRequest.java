package org.elibanking.accountservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.elibanking.accountservice.entity.AccountType;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {
    @Schema(example = "Alice Johnson")
    @NotBlank(message = "Account  name is required")
    private  String accountHolderName;

    @Schema(description = "Ignored for logged-in users: the email of the account owner (from the login token) is used",
            example = "alice@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Email(message = "Invalid email format")
    private  String email;

    @Schema(description = "Must be unique", example = "08012345678")
    @NotBlank(message = "phone number  is required")
    private  String phone;

    @Schema(example = "SAVINGS")
    @NotNull(message = "Account  type is required")
    private AccountType accountType;

    @Schema(description = "Opening balance", example = "10000")
    @NotNull(message = "Initial deposit  is required")
    @Positive(message = "Initial deposit must be positive")
    private BigDecimal initialDeposit;
}
