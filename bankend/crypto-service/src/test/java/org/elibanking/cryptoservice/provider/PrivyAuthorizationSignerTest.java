package org.elibanking.cryptoservice.provider;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrivyAuthorizationSignerTest {
    @Test
    void signsCanonicalPrivyRequestWithP256Key() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        String privateKey = "wallet-auth:" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        var signer = new PrivyAuthorizationSigner(new ObjectMapper());
        String encoded = signer.sign("POST", "https://api.privy.io/v1/wallets/abc/rpc",
                new ObjectMapper().readTree("{\"method\":\"eth_sendTransaction\",\"params\":{\"transaction\":{\"value\":\"0x1\"}}}"),
                Map.of("privy-app-id", "app", "privy-request-expiry", "123"), privateKey);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(("{\"body\":{\"method\":\"eth_sendTransaction\",\"params\":{\"transaction\":{\"value\":\"0x1\"}}},"
                + "\"headers\":{\"privy-app-id\":\"app\",\"privy-request-expiry\":\"123\"},\"method\":\"POST\","
                + "\"url\":\"https://api.privy.io/v1/wallets/abc/rpc\",\"version\":1}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(encoded)));
        assertDoesNotThrow(() -> Base64.getDecoder().decode(encoded));
    }

    @Test
    void refusesMissingAuthorizationKey() {
        var signer = new PrivyAuthorizationSigner(new ObjectMapper());
        assertThrows(IllegalStateException.class, () -> signer.sign("POST", "https://api.privy.io/v1/wallets",
                new ObjectMapper().createObjectNode(), Map.of("privy-app-id", "app"), ""));
    }
}
