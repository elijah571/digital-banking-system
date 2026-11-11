package org.elibanking.paymentservice.controller;

import org.elibanking.paymentservice.payout.FlutterwaveWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Flutterwave webhook endpoint. It acknowledges only after the event is durably recorded in the outbox (wallet-plan 9.6). */
@RestController
@RequestMapping("/webhooks/flutterwave")
public class FlutterwaveWebhookController {

    private final FlutterwaveWebhookService webhooks;

    public FlutterwaveWebhookController(FlutterwaveWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
                                        @RequestHeader(name = "verif-hash", required = false) String verifHash) {
        webhooks.verifySignature(verifHash);
        webhooks.record(rawBody);
        return ResponseEntity.ok().build();
    }
}
