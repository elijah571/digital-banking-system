package org.elibanking.cryptoservice.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.elibanking.common.web.RequestIdContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Privy Wallet API adapter. Calls are idempotent and owner-authorized. */
@Component
public class PrivyClient {
    private final boolean enabled;
    private final String baseUrl;
    private final String appId;
    private final String appSecret;
    private final String ownerId;
    private final String authorizationKey;
    private final RestClient http;
    private final ObjectMapper mapper;
    private final PrivyAuthorizationSigner signer;

    public PrivyClient(@Value("${privy.enabled:false}") boolean enabled,
            @Value("${privy.base-url:https://api.privy.io/v1}") String baseUrl,
            @Value("${privy.app-id:}") String appId, @Value("${privy.app-secret:}") String appSecret,
            @Value("${privy.authorization-key-id:}") String ownerId,
            @Value("${privy.authorization-private-key:}") String authorizationKey,
            ObjectMapper mapper, PrivyAuthorizationSigner signer) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.appId = appId;
        this.appSecret = appSecret;
        this.ownerId = ownerId;
        this.authorizationKey = authorizationKey;
        this.mapper = mapper;
        this.signer = signer;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(requestFactory).build();
        if (!this.baseUrl.startsWith("https://") || this.baseUrl.contains("@"))
            throw new IllegalArgumentException("Privy base URL must use HTTPS");
        if (enabled && (appId.isBlank() || appSecret.isBlank() || ownerId.isBlank() || authorizationKey.isBlank()))
            throw new IllegalStateException("PRIVY_APP_ID, PRIVY_APP_SECRET, PRIVY_AUTHORIZATION_KEY_QUORUM_ID and PRIVY_AUTHORIZATION_PRIVATE_KEY are required when Privy is enabled");
    }

    public JsonNode createEvmWallet(String externalId, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chain_type", "ethereum");
        body.put("external_id", externalId);
        body.put("owner_id", ownerId);
        body.put("display_name", "EliPay customer wallet");
        return post("/wallets", body, idempotencyKey);
    }

    public JsonNode wallet(String walletId) { return get("/wallets/" + segment(walletId)); }

    public JsonNode sendEvmTransaction(String walletId, int chainId, Map<String, String> transaction, String referenceId, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caip2", "eip155:" + chainId);
        body.put("method", "eth_sendTransaction");
        body.put("params", Map.of("transaction", transaction));
        body.put("reference_id", referenceId);
        body.put("chain_type", "ethereum");
        return post("/wallets/" + segment(walletId) + "/rpc", body, idempotencyKey);
    }

    public JsonNode transaction(String transactionId) { return get("/transactions/" + segment(transactionId)); }

    public JsonNode get(String path) {
        requireEnabled();
        try {
            return http.get().uri(baseUrl + path).headers(this::authHeaders).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) throw new PrivyNotFound();
            throw new PrivyUnavailable("Privy API request failed with HTTP " + ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            if (ex instanceof PrivyUnavailable || ex instanceof PrivyNotFound) throw ex;
            throw new PrivyUnavailable("Privy API request could not be completed");
        }
    }

    private JsonNode post(String path, Map<String, ?> requestBody, String idempotencyKey) {
        requireEnabled();
        try {
            JsonNode body = mapper.valueToTree(requestBody);
            String url = baseUrl + path;
            long expiry = System.currentTimeMillis() + 60_000;
            Map<String, String> signedHeaders = new LinkedHashMap<>();
            signedHeaders.put("privy-app-id", appId);
            signedHeaders.put("privy-request-expiry", Long.toString(expiry));
            if (idempotencyKey != null && !idempotencyKey.isBlank()) signedHeaders.put("privy-idempotency-key", idempotencyKey);
            String authorizationSignature = signer.sign("POST", url, body, signedHeaders, authorizationKey);
            return http.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        authHeaders(headers);
                        headers.set("privy-request-expiry", Long.toString(expiry));
                        headers.set("privy-authorization-signature", authorizationSignature);
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) headers.set("privy-idempotency-key", idempotencyKey);
                        RequestIdContext.apply(headers);
                    }).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw new PrivyUnavailable("Privy API request failed with HTTP " + ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            if (ex instanceof PrivyUnavailable) throw ex;
            throw new PrivyUnavailable("Privy API request could not be completed");
        }
    }

    private void authHeaders(org.springframework.http.HttpHeaders headers) {
        String encoded = Base64.getEncoder().encodeToString((appId + ":" + appSecret).getBytes(StandardCharsets.UTF_8));
        headers.set(org.springframework.http.HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        headers.set("privy-app-id", appId);
        RequestIdContext.apply(headers);
    }

    private void requireEnabled() { if (!enabled) throw new PrivyUnavailable("Privy integration is not enabled"); }
    private static String segment(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    public static class PrivyUnavailable extends RuntimeException { public PrivyUnavailable(String message) { super(message); } }
    public static final class PrivyNotFound extends RuntimeException {}
}
