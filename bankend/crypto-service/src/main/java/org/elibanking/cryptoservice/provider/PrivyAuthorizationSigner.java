package org.elibanking.cryptoservice.provider;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/** Creates Privy's authorization signature for a wallet API request. */
@Component
public class PrivyAuthorizationSigner {
    private final ObjectMapper mapper;

    public PrivyAuthorizationSigner(ObjectMapper mapper) { this.mapper = mapper; }

    public String sign(String method, String url, JsonNode body, Map<String, String> privyHeaders, String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("Privy wallet authorization key is not configured");
        try {
            Map<String, Object> payload = new TreeMap<>();
            payload.put("version", 1);
            payload.put("method", method);
            payload.put("url", url);
            payload.put("body", body);
            payload.put("headers", new TreeMap<>(privyHeaders));
            byte[] canonical = canonical(mapper.valueToTree(payload)).getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey(key));
            signer.update(canonical);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign Privy wallet request");
        }
    }

    private PrivateKey privateKey(String supplied) throws Exception {
        String value = supplied.startsWith("wallet-auth:") ? supplied.substring("wallet-auth:".length()) : supplied;
        byte[] der;
        if (value.contains("BEGIN PRIVATE KEY")) {
            String base64 = value.replace("\\n", "\n").replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "").replaceAll("\\s", "");
            der = Base64.getDecoder().decode(base64);
        } else der = Base64.getDecoder().decode(value);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (var entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(entry.getKey())).append(':').append(canonical(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                out.append(canonical(node.get(i)));
            }
            return out.append(']').toString();
        }
        if (node.isTextual()) return quote(node.asText());
        return node.toString();
    }

    private String quote(String value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not canonicalize Privy request"); }
    }
}
