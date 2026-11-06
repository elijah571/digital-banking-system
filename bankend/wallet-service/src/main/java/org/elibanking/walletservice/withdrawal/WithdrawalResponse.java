package org.elibanking.walletservice.withdrawal;

/** A withdrawal as the client sees it. {@code failureReason} is set once the withdrawal has failed. */
public record WithdrawalResponse(long payoutId, String status, String accountNumber, String amount, String fee, String currency,
                                 String bankCode, String destinationAccountNumber, String destinationAccountName,
                                 String failureReason) {
}
