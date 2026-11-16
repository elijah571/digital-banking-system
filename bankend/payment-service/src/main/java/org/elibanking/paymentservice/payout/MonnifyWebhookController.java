package org.elibanking.paymentservice.payout;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class MonnifyWebhookController {
    private final MonnifyWebhookService webhooks;

    public MonnifyWebhookController(MonnifyWebhookService webhooks) { this.webhooks = webhooks; }

    @PostMapping("/monnify")
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
                                        @RequestHeader(name = "monnify-signature", required = false) String signature) {
        webhooks.record(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
