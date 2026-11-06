package org.elibanking.paymentservice.controller;

import jakarta.validation.Valid;
import org.elibanking.paymentservice.dto.DepositIntentRequest;
import org.elibanking.paymentservice.dto.DepositIntentResponse;
import org.elibanking.paymentservice.stripe.StripeIntents;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

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
}
