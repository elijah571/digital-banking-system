package org.elibanking.walletservice.deposit;

/**
 * The deposit as the client sees it. {@code clientSecret} is what Stripe.js needs to confirm the card payment, and it is null
 * once the deposit no longer needs a payment.
 */
public record DepositResponse(String depositId, String status, String accountNumber, String amount, String currency,
                              String paymentIntentId, String clientSecret) {
}
