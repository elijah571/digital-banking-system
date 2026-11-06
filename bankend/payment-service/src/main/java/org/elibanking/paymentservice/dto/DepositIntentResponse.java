package org.elibanking.paymentservice.dto;

/** What the wallet needs to hand the card form to the user: the intent id, and the client secret for Stripe.js. */
public record DepositIntentResponse(String paymentIntentId, String clientSecret) {
}
