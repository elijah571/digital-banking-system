package org.elibanking.paymentservice.payout;

import java.math.BigDecimal;
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

    /** What the provider answered to a send: ACCEPTED, PENDING or FAILED, with the provider's own reference. */
    enum SendStatus {
        ACCEPTED,
        PENDING,
        FAILED
    }

    record SendResult(String providerRef, SendStatus status) {
    }

    /**
     * The status of a send, asked of the provider. The send's own answer is not enough to settle (wallet-plan 8.3). REVERSED is the
     * bank taking the money back after success. NOT_FOUND means the provider has no send under that key.
     */
    enum PayoutStatus {
        SUCCESS,
        PENDING,
        FAILED,
        REVERSED,
        NOT_FOUND
    }

    List<Bank> listBanks();

    Resolution resolveAccount(String bankCode, String accountNumber);

    /** Sends money under the key. A send with a key that was sent before returns the first answer and sends nothing new (8.7). */
    SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName, String narration);

    PayoutStatus getStatus(String key);
}
