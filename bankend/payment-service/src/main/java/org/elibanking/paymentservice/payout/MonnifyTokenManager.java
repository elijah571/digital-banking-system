package org.elibanking.paymentservice.payout;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/** Thread-safe, in-memory Monnify bearer token cache. Tokens are refreshed one minute before expiry. */
public final class MonnifyTokenManager {

    private static final long EXPIRY_SAFETY_SECONDS = 60;

    private record CachedToken(String value, Instant refreshAt) { }

    private final RestClient client;
    private final String apiKey;
    private final String secretKey;
    private final Clock clock;
    private volatile CachedToken cached;

    public MonnifyTokenManager(RestClient client, String apiKey, String secretKey) {
        this(client, apiKey, secretKey, Clock.systemUTC());
    }

    MonnifyTokenManager(RestClient client, String apiKey, String secretKey, Clock clock) {
        if (apiKey == null || apiKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Monnify API credentials must be configured");
        }
        this.client = client;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.clock = clock;
    }

    public String getToken() {
        Instant now = clock.instant();
        CachedToken current = cached;
        if (current != null && now.isBefore(current.refreshAt())) {
            return current.value();
        }
        synchronized (this) {
            now = clock.instant();
            current = cached;
            if (current != null && now.isBefore(current.refreshAt())) {
                return current.value();
            }
            return fetchToken(now);
        }
    }

    private String fetchToken(Instant now) {
        String credentials = Base64.getEncoder().encodeToString(
                (apiKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        JsonNode response = client.post().uri("/api/v1/auth/login")
                .header("Authorization", "Basic " + credentials)
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve().body(JsonNode.class);
        JsonNode body = response == null ? null : response.path("responseBody");
        String token = body == null ? "" : body.path("accessToken").asText("");
        long expiresIn = body == null ? 0 : body.path("expiresIn").asLong(0);
        if (response == null || !response.path("requestSuccessful").asBoolean(false)
                || token.isBlank() || expiresIn <= 0) {
            throw new IllegalStateException("Monnify token response was invalid");
        }
        long cacheSeconds = Math.max(0, expiresIn - EXPIRY_SAFETY_SECONDS);
        cached = new CachedToken(token, now.plusSeconds(cacheSeconds));
        return token;
    }
}
