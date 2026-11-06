package org.elibanking.walletservice.withdrawal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A withdrawal from one of the caller's accounts to an account at another bank (wallet-plan 8.3). The destination comes from a name
 * enquiry, whose reference is checked here. The PIN is part of the request and is never written to a log: {@link #toString} hides it.
 */
public record WithdrawalRequest(
        @NotBlank @Pattern(regexp = "^\\d{10}$", message = "accountNumber must be 10 digits") String accountNumber,
        @NotBlank @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "amount must be a number with two decimals") String amount,
        @NotBlank @Size(max = 20) String bankCode,
        @NotBlank @Pattern(regexp = "^\\d{10}$", message = "destinationAccountNumber must be 10 digits") String destinationAccountNumber,
        @NotBlank @Size(max = 64) String nameEnquiryRef,
        @Size(max = 255) String narration,
        @NotBlank @Pattern(regexp = "^\\d{4}$", message = "pin must be 4 digits") String pin) {

    @Override
    public String toString() {
        return "WithdrawalRequest[accountNumber=" + accountNumber + ", amount=" + amount + ", bankCode=" + bankCode
                + ", destinationAccountNumber=" + destinationAccountNumber + ", pin=****]";
    }
}
