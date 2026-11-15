package org.elibanking.cryptoservice.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FireblocksWebhookVerifierTest {
    @Test
    void acceptsOfficialEnvironmentJwksEndpoints() {
        assertDoesNotThrow(() -> new FireblocksWebhookVerifier("https://sandbox-keys.fireblocks.io/.well-known/jwks.json"));
        assertDoesNotThrow(() -> new FireblocksWebhookVerifier("https://eu-keys.fireblocks.io/.well-known/jwks.json"));
        assertDoesNotThrow(() -> new FireblocksWebhookVerifier("https://eu2-keys.fireblocks.io/.well-known/jwks.json"));
        assertDoesNotThrow(() -> new FireblocksWebhookVerifier("https://keys.fireblocks.io/.well-known/jwks.json"));
    }

    @Test
    void refusesNonTlsAndNonFireblocksJwksUrls() {
        assertThrows(IllegalArgumentException.class, () -> new FireblocksWebhookVerifier("http://sandbox-keys.fireblocks.io/.well-known/jwks.json"));
        assertThrows(IllegalArgumentException.class, () -> new FireblocksWebhookVerifier("https://example.com/.well-known/jwks.json"));
        assertThrows(IllegalArgumentException.class, () -> new FireblocksWebhookVerifier("https://sandbox-keys.fireblocks.io.evil.example/.well-known/jwks.json"));
    }

    @Test
    void rejectsEmptyOrMalformedDetachedSignaturesWithoutNetworkAccess() {
        FireblocksWebhookVerifier verifier = new FireblocksWebhookVerifier("https://sandbox-keys.fireblocks.io/.well-known/jwks.json");
        assertFalse(verifier.verify(new byte[0], "header..signature"));
        assertFalse(verifier.verify("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        assertFalse(verifier.verify("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "not-a-jws"));
    }
}
