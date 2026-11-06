package org.elibanking.paymentservice.payout;

import java.util.List;

/**
 * The payout provider (wallet-plan 8.7). This slice needs the bank list and the name enquiry. Sending money and checking its status
 * come with the payout slice, and the Paystack adapter comes in phase 6b.
 */
public interface PayoutProvider {

    /** A bank the provider can send to. */
    record Bank(String name, String code) {
    }

    /** What the provider found for an account number at a bank. */
    enum Outcome {
        FOUND,
        NOT_FOUND,
        UNAVAILABLE
    }

    /** The outcome, and the account holder's name when it was found. */
    record Resolution(Outcome outcome, String accountName) {
    }

    List<Bank> listBanks();

    Resolution resolveAccount(String bankCode, String accountNumber);
}
