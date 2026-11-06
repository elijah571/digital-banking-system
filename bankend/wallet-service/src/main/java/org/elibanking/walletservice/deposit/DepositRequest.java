package org.elibanking.walletservice.deposit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A card deposit into one of the caller's accounts. The amount is a string of naira with two decimals, as the money rule
 * requires (wallet-plan D3). Only NGN is accepted, so there is no currency field.
 */
public record DepositRequest(
        @NotBlank @Pattern(regexp = "^\\d{10}$", message = "accountNumber must be 10 digits") String accountNumber,
        @NotBlank @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "amount must be a number with two decimals") String amount) {
}
