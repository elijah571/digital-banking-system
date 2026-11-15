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
import java.net.URI;
import java.util.Set;

/** Verifies the current detached-JWS Fireblocks Webhooks V2 signature against the environment JWKS. */
@Component
public class FireblocksWebhookVerifier {
    private static final Set<String> FIREBLOCKS_KEY_HOSTS = Set.of(
            "keys.fireblocks.io", "eu-keys.fireblocks.io", "eu2-keys.fireblocks.io", "sandbox-keys.fireblocks.io");
    private final String jwksUrl;
    private final RestClient http = RestClient.create();
    private volatile JWKSet cached;
    private volatile Instant cachedAt = Instant.EPOCH;
    public FireblocksWebhookVerifier(@Value("${fireblocks.webhook-jwks-url}") String jwksUrl) {
        URI uri;
        try { uri = URI.create(jwksUrl); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid Fireblocks webhook JWKS URL", invalid); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !FIREBLOCKS_KEY_HOSTS.contains(uri.getHost())
                || !"/.well-known/jwks.json".equals(uri.getPath()) || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Fireblocks webhook JWKS URL must be an official HTTPS environment endpoint");
        }
        this.jwksUrl = uri.toString();
    }

    public boolean verify(byte[] rawBody, String detachedSignature) {
        if (rawBody == null || rawBody.length == 0 || detachedSignature == null) return false;
        try {
            String[] parts = detachedSignature.split("\\.", -1);
            if (parts.length != 3 || !parts[1].isEmpty()) return false;
            String payload = Base64URL.encode(rawBody).toString();
            JWSObject jws = JWSObject.parse(parts[0] + "." + payload + "." + parts[2]);
            if (!JWSAlgorithm.RS512.equals(jws.getHeader().getAlgorithm())) return false;
            JWK key = jwks().getKeyByKeyId(jws.getHeader().getKeyID());
            if (key == null) key = refresh().getKeyByKeyId(jws.getHeader().getKeyID());
            return key instanceof RSAKey rsa && jws.verify(new RSASSAVerifier(rsa.toRSAPublicKey()));
        } catch (WebhookVerificationUnavailable unavailable) { throw unavailable; }
        catch (Exception invalid) { return false; }
    }

    private JWKSet jwks() {
        JWKSet value = cached;
        if (value != null && Instant.now().isBefore(cachedAt.plusSeconds(600))) return value;
        synchronized (this) {
            if (cached != null && Instant.now().isBefore(cachedAt.plusSeconds(600))) return cached;
            return refreshLocked();
        }
    }

    private JWKSet refresh() {
        synchronized (this) { return refreshLocked(); }
    }

    private JWKSet refreshLocked() {
        try {
            String json = http.get().uri(jwksUrl).retrieve().body(String.class);
            if (json == null || json.isBlank()) throw new IllegalStateException("Empty JWKS response");
            cached = JWKSet.parse(json); cachedAt = Instant.now(); return cached;
        } catch (Exception unavailable) { throw new WebhookVerificationUnavailable(unavailable); }
    }

    public static final class WebhookVerificationUnavailable extends RuntimeException {
        public WebhookVerificationUnavailable(Throwable cause) { super("Fireblocks webhook verification keys are temporarily unavailable", cause); }
    }
}
