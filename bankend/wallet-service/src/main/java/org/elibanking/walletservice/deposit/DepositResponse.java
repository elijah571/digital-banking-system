package org.elibanking.walletservice.deposit;

/**
 * The deposit as the client sees it. {@code clientSecret} is what Stripe.js needs to confirm the card payment. It is sent only
 * with the first response, and it is null once the deposit no longer needs a payment, and on a read. {@code failureReason} is set
 * once the deposit has failed.
 */
public record DepositResponse(String depositId, String status, String accountNumber, String amount, String currency,
                              String paymentIntentId, String clientSecret, String failureReason) {
}
