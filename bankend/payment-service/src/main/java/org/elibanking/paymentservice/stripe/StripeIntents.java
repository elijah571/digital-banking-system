package org.elibanking.paymentservice.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Component;

/**
 * Creates the card PaymentIntent for a wallet deposit (wallet-plan 3.5, G1, D27). It is in NGN, takes card only, and carries the
 * deposit id as metadata. The Stripe idempotency key is the deposit id, so a retried request makes one intent, not two.
 * The card is the only method, because the NGN card path through Stripe's partner is not enabled on our account (8.8).
 */
@Component
public class StripeIntents {

    public record Created(String paymentIntentId, String clientSecret) {
    }

    public Created create(String depositId, long amountInKobo, String currency) throws StripeException {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountInKobo)
                .setCurrency(currency.toLowerCase())
                .addPaymentMethodType("card")
                .putMetadata("depositId", depositId)
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("deposit-intent-" + depositId)
                .build();
        PaymentIntent intent = PaymentIntent.create(params, options);
        return new Created(intent.getId(), intent.getClientSecret());
    }
}
