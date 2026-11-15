package org.elibanking.cryptoservice.provider;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/** Verifies the current detached-JWS Fireblocks Webhooks V2 signature against the environment JWKS. */
@Component
public class FireblocksWebhookVerifier {
    private final String jwksUrl;
    private final RestClient http = RestClient.create();
    private volatile JWKSet cached;
    private volatile Instant cachedAt = Instant.EPOCH;
    public FireblocksWebhookVerifier(@Value("${fireblocks.webhook-jwks-url}") String jwksUrl) { this.jwksUrl = jwksUrl; }

    public boolean verify(byte[] rawBody, String detachedSignature) {
        if (rawBody == null || rawBody.length == 0 || detachedSignature == null) return false;
        try {
            String[] parts = detachedSignature.split("\\.", -1);
            if (parts.length != 3 || !parts[1].isEmpty()) return false;
            String payload = Base64URL.encode(rawBody).toString();
            JWSObject jws = JWSObject.parse(parts[0] + "." + payload + "." + parts[2]);
            if (!JWSAlgorithm.RS512.equals(jws.getHeader().getAlgorithm())) return false;
            JWK key = jwks().getKeyByKeyId(jws.getHeader().getKeyID());
            return key instanceof RSAKey rsa && jws.verify(new RSASSAVerifier(rsa.toRSAPublicKey()));
        } catch (Exception ignored) { return false; }
    }

    private JWKSet jwks() throws Exception {
        JWKSet value = cached;
        if (value != null && Instant.now().isBefore(cachedAt.plusSeconds(600))) return value;
        synchronized (this) {
            if (cached != null && Instant.now().isBefore(cachedAt.plusSeconds(600))) return cached;
            String json = http.get().uri(jwksUrl).retrieve().body(String.class);
            if (json == null || json.isBlank()) throw new IllegalStateException("Empty Fireblocks JWKS response");
            cached = JWKSet.parse(json); cachedAt = Instant.now(); return cached;
        }
    }
}
