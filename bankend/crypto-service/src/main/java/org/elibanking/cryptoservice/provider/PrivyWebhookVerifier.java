package org.elibanking.cryptoservice.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** Verifies Privy's Svix webhook signature and rejects stale/replayed timestamp windows. */
@Component
public class PrivyWebhookVerifier {
    private static final long TOLERANCE_SECONDS = 300;
    private final String secret;

    public PrivyWebhookVerifier(@Value("${privy.webhook-signing-secret:}") String secret) { this.secret = secret; }

    public boolean isConfigured() {
        if (secret == null || !secret.startsWith("whsec_")) return false;
        try { return Base64.getDecoder().decode(secret.substring("whsec_".length())).length > 0; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public boolean verify(byte[] body, String eventId, String timestamp, String signatures) {
        if (!isConfigured() || body == null || body.length == 0
                || eventId == null || eventId.isBlank() || timestamp == null || signatures == null) return false;
        try {
            long epoch = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - epoch) > TOLERANCE_SECONDS) return false;
            byte[] key = Base64.getDecoder().decode(secret.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((eventId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = mac.doFinal(body);
            for (String item : signatures.split(" ")) {
                String[] parts = item.split(",", 2);
                if (parts.length == 2 && "v1".equals(parts[0])) {
                    byte[] supplied = Base64.getDecoder().decode(parts[1]);
                    if (MessageDigest.isEqual(expected, supplied)) return true;
                }
            }
            return false;
        } catch (Exception invalid) { return false; }
    }
}
