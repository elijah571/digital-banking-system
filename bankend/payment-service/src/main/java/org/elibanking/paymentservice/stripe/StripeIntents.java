package org.elibanking.paymentservice.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Creates the card PaymentIntent for a wallet deposit (wallet-plan 3.5, G1, D27). It is in NGN, takes card only, and carries the
 * deposit id as metadata. The Stripe idempotency key is the deposit id, so a retried request makes one intent, not two.
 * The card is the only method, because the NGN card path through Stripe's partner is not enabled on our account (8.8).
 */
@Component
public class StripeIntents {

    public record Created(String paymentIntentId, String clientSecret) {
    }

    /** What Stripe answered to a cancel: "canceled", or the status that says why the intent is not cancelled, such as "succeeded". */
    public record Cancelled(String status) {
    }

    /** The statuses in which Stripe cancels an intent. A succeeded or processing intent is not one of them. */
    private static final Set<String> CANCELLABLE = Set.of("requires_payment_method", "requires_confirmation", "requires_action");

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

    /**
     * Cancels a deposit's intent at Stripe (wallet-plan 3.5, step 6). It answers "canceled" when the intent is canceled now or was
     * canceled before. Otherwise it answers the status Stripe reports, which says why the intent is not cancelled.
     */
    public Cancelled cancel(String paymentIntentId) throws StripeException {
        PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
        if ("canceled".equals(intent.getStatus())) {
            return new Cancelled("canceled");
        }
        if (!CANCELLABLE.contains(intent.getStatus())) {
            return new Cancelled(intent.getStatus());
        }
        return new Cancelled(intent.cancel().getStatus());
    }
}
