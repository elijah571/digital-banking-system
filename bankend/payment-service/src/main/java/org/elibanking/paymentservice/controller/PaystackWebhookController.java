package org.elibanking.paymentservice.controller;

import org.elibanking.paymentservice.payout.PaystackWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Paystack webhook endpoint. It acknowledges only after the event is durably recorded in the outbox. */
@RestController
@RequestMapping("/webhooks/paystack")
public class PaystackWebhookController {

    private final PaystackWebhookService webhooks;

    public PaystackWebhookController(PaystackWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
                                         @RequestHeader(name = "x-paystack-signature", required = false) String signature) {
        webhooks.verifySignature(rawBody, signature);
        webhooks.record(rawBody);
        return ResponseEntity.ok().build();
    }
}
