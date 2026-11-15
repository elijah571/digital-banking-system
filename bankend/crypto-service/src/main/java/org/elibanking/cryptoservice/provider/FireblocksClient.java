package org.elibanking.cryptoservice.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.elibanking.common.web.RequestIdContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Small server-side Fireblocks REST adapter. It signs the exact body and URI sent on the wire. */
@Component
public class FireblocksClient {
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String privateKeyPem;
    private final RestClient client;
    private final ObjectMapper mapper;

    public FireblocksClient(@Value("${fireblocks.enabled:false}") boolean enabled,
            @Value("${fireblocks.base-url}") String baseUrl, @Value("${fireblocks.api-key:}") String apiKey,
            @Value("${fireblocks.private-key:}") String privateKeyPem, ObjectMapper mapper) {
        this.enabled = enabled; this.baseUrl = baseUrl.replaceAll("/$", ""); this.apiKey = apiKey;
        this.privateKeyPem = privateKeyPem; this.mapper = mapper; this.client = RestClient.create();
        if (enabled && (apiKey.isBlank() || privateKeyPem.isBlank())) throw new IllegalStateException("Fireblocks API key and private key are required when enabled");
        if (!this.baseUrl.startsWith("https://") || this.baseUrl.contains("@")) throw new IllegalArgumentException("Fireblocks base URL must be HTTPS");
    }

    public JsonNode createVault(String customerRef) {
        String shortRef = sha(customerRef).substring(0, 24);
        Map<String,Object> body = new LinkedHashMap<>(); body.put("name", "eli-" + shortRef); body.put("hiddenOnUI", false); body.put("customerRefId", shortRef);
        return post("/v1/vault/accounts", body, "vault-" + sha(customerRef));
    }

    public JsonNode ensureAssetWallet(String vaultId, String assetId) {
        String path = "/v1/vault/accounts/" + segment(vaultId) + "/" + segment(assetId);
        try { return call("GET", path, null, null); }
        catch (ProviderNotFound missing) { return call("PUT", path, Map.of(), "wallet-" + vaultId + "-" + assetId); }
    }

    public JsonNode createAddress(String vaultId, String assetId, String description, String idempotencyKey) {
        String path = "/v1/vault/accounts/" + segment(vaultId) + "/" + segment(assetId) + "/addresses";
        return post(path, Map.of("description", description), idempotencyKey);
    }

    public JsonNode createTransaction(String assetId, String vaultId, String amount, String address, String tag,
                                      String externalTxId, String note) {
        Map<String,Object> destination = new LinkedHashMap<>(); destination.put("type", "ONE_TIME_ADDRESS");
        Map<String,Object> oneTime = new LinkedHashMap<>(); oneTime.put("address", address); if (tag != null && !tag.isBlank()) oneTime.put("tag", tag);
        destination.put("oneTimeAddress", oneTime);
        Map<String,Object> source = Map.of("type", "VAULT_ACCOUNT", "id", vaultId);
        Map<String,Object> body = new LinkedHashMap<>(); body.put("assetId", assetId); body.put("amount", amount); body.put("source", source);
        body.put("destination", destination); body.put("externalTxId", externalTxId); body.put("note", note);
        return post("/v1/transactions", body, "tx-" + externalTxId);
    }

    public JsonNode transaction(String txId) { return call("GET", "/v1/transactions/" + segment(txId), null, null); }
    public JsonNode transactionByExternalId(String externalId) {
        return call("GET", "/v1/transactions/external_tx_id/" + segment(externalId), null, null);
    }

    public JsonNode call(String method, String uri, Map<String,?> body, String idempotencyKey) {
        requireEnabled();
        String json = body == null ? "" : serialize(body);
        String authorization = "Bearer " + signedJwt(uri, json);
        try {
            RestClient.RequestBodySpec request = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(baseUrl + uri)
                    .headers(headers -> { headers.set("X-API-Key", apiKey); headers.setBearerAuth(authorization.substring(7));
                        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey); RequestIdContext.apply(headers); });
            if (json.isEmpty()) return request.retrieve().body(JsonNode.class);
            return request.contentType(MediaType.APPLICATION_JSON).body(json).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) throw new ProviderNotFound();
            throw new FireblocksUnavailable("Fireblocks request failed with HTTP " + ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            if (ex instanceof FireblocksUnavailable || ex instanceof ProviderNotFound) throw ex;
            throw new FireblocksUnavailable("Fireblocks request could not be completed");
        }
    }

    private JsonNode post(String uri, Map<String,?> body, String idempotencyKey) { return call("POST", uri, body, idempotencyKey); }
    private void requireEnabled() { if (!enabled) throw new FireblocksUnavailable("Fireblocks integration is not enabled"); }
    private String serialize(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Could not serialize Fireblocks request"); } }
    private String signedJwt(String uri, String body) {
        long now = Instant.now().getEpochSecond();
        String bodyHash = shaBytes(body.getBytes(StandardCharsets.UTF_8));
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String claims = "{\"uri\":" + quote(uri) + ",\"nonce\":" + quote(UUID.randomUUID().toString())
                + ",\"iat\":" + now + ",\"exp\":" + (now + 25) + ",\"sub\":" + quote(apiKey)
                + ",\"bodyHash\":" + quote(bodyHash) + "}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String signingInput = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(privateKey()); signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + encoder.encodeToString(signer.sign());
        } catch (Exception e) { throw new IllegalStateException("Could not sign Fireblocks API request"); }
    }
    private PrivateKey privateKey() throws Exception {
        String key = privateKeyPem.replace("\\n", "\n").replaceAll("-----BEGIN [A-Z ]+-----", "").replaceAll("-----END [A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(key);
        if (privateKeyPem.contains("BEGIN RSA PRIVATE KEY")) der = pkcs1ToPkcs8(der);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
    private static byte[] pkcs1ToPkcs8(byte[] key) {
        byte[] alg = hex("300d06092a864886f70d0101010500");
        byte[] octet = tlv(0x04, key); byte[] version = hex("020100");
        byte[] content = concat(version, alg, octet); return tlv(0x30, content);
    }
    private static byte[] tlv(int tag, byte[] bytes) { return concat(new byte[]{(byte)tag}, length(bytes.length), bytes); }
    private static byte[] length(int n) {
        if (n < 128) return new byte[]{(byte)n};
        byte[] raw = BigInteger.valueOf(n).toByteArray(); int start = raw.length > 1 && raw[0] == 0 ? 1 : 0;
        byte[] out = new byte[1 + raw.length - start]; out[0] = (byte)(0x80 | (raw.length - start)); System.arraycopy(raw,start,out,1,raw.length-start); return out;
    }
    private static byte[] concat(byte[]... parts) { int size=0; for(byte[] p:parts) size+=p.length; byte[] o=new byte[size]; int at=0; for(byte[] p:parts){System.arraycopy(p,0,o,at,p.length);at+=p.length;} return o; }
    private static byte[] hex(String text) { byte[] out=new byte[text.length()/2]; for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16); return out; }
    private static String sha(String s) { return shaBytes(s.getBytes(StandardCharsets.UTF_8)); }
    private static String shaBytes(byte[] b) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b)); } catch(Exception e){throw new IllegalStateException(e);} }
    private static String segment(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    public static final class ProviderNotFound extends RuntimeException {}
    public static final class FireblocksUnavailable extends RuntimeException { public FireblocksUnavailable(String message) { super(message); } }
}
