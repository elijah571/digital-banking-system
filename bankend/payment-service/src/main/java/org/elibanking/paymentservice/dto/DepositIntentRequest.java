package org.elibanking.paymentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A deposit's card payment: the wallet's deposit id, the amount in naira with two decimals, and the currency. Only NGN is
 * accepted (wallet-plan G1, 3.5).
 */
public record DepositIntentRequest(
        @NotBlank @Size(max = 100) String depositId,
        @NotBlank @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "amount must be a number with two decimals") String amount,
        @NotBlank @Pattern(regexp = "^NGN$", message = "only NGN deposits are accepted") String currency) {
}
