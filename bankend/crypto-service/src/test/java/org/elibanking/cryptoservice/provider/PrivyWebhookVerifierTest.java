package org.elibanking.cryptoservice.provider;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivyWebhookVerifierTest {
    @Test
    void verifiesSvixSignatureAndRejectsTamperingAndStaleEvents() throws Exception {
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 7);
        String configured = "whsec_" + Base64.getEncoder().encodeToString(secret);
        byte[] body = "{\"type\":\"transaction.confirmed\"}".getBytes(StandardCharsets.UTF_8);
        String eventId = "evt_123";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update((eventId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
        String signature = "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        PrivyWebhookVerifier verifier = new PrivyWebhookVerifier(configured);

        assertTrue(verifier.verify(body, eventId, timestamp, signature));
        assertFalse(verifier.verify("{}".getBytes(StandardCharsets.UTF_8), eventId, timestamp, signature));
        assertFalse(verifier.verify(body, eventId, "1", signature));
    }

    @Test
    void rejectsAbsentOrMalformedSigningSecret() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertFalse(new PrivyWebhookVerifier("").verify(body, "evt", "1", "v1,aQ=="));
        assertFalse(new PrivyWebhookVerifier("invalid").verify(body, "evt", "1", "v1,aQ=="));
    }
}
