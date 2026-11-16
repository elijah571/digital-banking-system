package org.elibanking.paymentservice.payout;

import java.math.BigDecimal;
import java.util.List;

/**
 * The payout provider contract shared by withdrawals and external transfers (wallet-plan 8.7, 9.1). The simulator is the test/CI/demo
 * default; the Flutterwave V3 adapter is the preferred external provider (wallet-plan 9).
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

    record SendResult(String providerRef, SendStatus status, String providerName) {
        public SendResult(String providerRef, SendStatus status) {
            this(providerRef, status, null);
        }
    }

    /** Provider-neutral bank payout command. */
    record PayoutRequest(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName,
                         String narration) {
    }

    /** Status plus the provider facts wallet must match before it can settle or reverse a payout. */
    record StatusResult(PayoutStatus status, String reference, BigDecimal amount, String currency) {
    }

    /** Stable configured rail name used in routing and the wallet payout audit trail. */
    String getProviderName();

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

    default Resolution validateAccount(String bankCode, String accountNumber) {
        return resolveAccount(bankCode, accountNumber);
    }

    Resolution resolveAccount(String bankCode, String accountNumber);

    /** Sends money under the key. A send with a key that was sent before returns the first answer and sends nothing new (8.7). */
    SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName, String narration);

    default SendResult initiatePayout(PayoutRequest request) {
        return send(request.key(), request.amount(), request.bankCode(), request.accountNumber(), request.accountName(), request.narration());
    }

    PayoutStatus getStatus(String key);

    default StatusResult getStatusDetails(String key) {
        return new StatusResult(getStatus(key), key, null, null);
    }

    default StatusResult checkStatus(String key) {
        return getStatusDetails(key);
    }

    default StatusResult checkStatus(String key, String providerName) {
        return checkStatus(key);
    }
}
