package org.elibanking.paymentservice.controller;

import jakarta.validation.Valid;
import org.elibanking.paymentservice.dto.DepositIntentRequest;
import org.elibanking.paymentservice.dto.DepositIntentResponse;
import org.elibanking.paymentservice.stripe.StripeIntents;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/** Internal API for wallet-service (wallet-plan 3.5). Only wallet-service may call it, see SecurityConfig. */
@RestController
@RequestMapping("/internal/v1/payments")
public class InternalPaymentController {

    private final StripeIntents intents;

    public InternalPaymentController(StripeIntents intents) {
        this.intents = intents;
    }

    /** Creates the card intent for a deposit. The amount is converted to kobo with no rounding (two decimals in, exact out). */
    @PostMapping("/intents")
    public ResponseEntity<DepositIntentResponse> createDepositIntent(@Valid @RequestBody DepositIntentRequest request)
            throws com.stripe.exception.StripeException {
        long kobo = new BigDecimal(request.amount()).movePointRight(2).longValueExact();
        StripeIntents.Created created = intents.create(request.depositId(), kobo, request.currency());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new DepositIntentResponse(created.paymentIntentId(), created.clientSecret()));
    }

    /**
     * Cancels a deposit's intent at Stripe (wallet-plan 3.5, step 6). 200 with "canceled" when it is canceled now or already was. 409
     * with Stripe's status when Stripe will not cancel it, such as "succeeded". The deposit then stays as it is.
     */
    @PostMapping("/intents/{paymentIntentId}/cancel")
    public ResponseEntity<Map<String, String>> cancelDepositIntent(@PathVariable String paymentIntentId)
            throws com.stripe.exception.StripeException {
        if (!paymentIntentId.startsWith("pi_")) {
            throw new IllegalArgumentException("paymentIntentId must be a Stripe payment intent id");
        }
        StripeIntents.Cancelled result = intents.cancel(paymentIntentId);
        HttpStatus status = "canceled".equals(result.status()) ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("status", result.status()));
    }
}
