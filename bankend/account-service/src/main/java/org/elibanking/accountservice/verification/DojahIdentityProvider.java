package org.elibanking.accountservice.verification;

import org.elibanking.accountservice.exception.AccountOperationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient.ResponseSpec;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class DojahIdentityProvider {

    public record DocumentResult(boolean matched, BigDecimal confidenceScore, String detectedDocumentType) {
    }

    public record SelfieResult(boolean matched, BigDecimal confidenceScore) {
    }

    public record LivenessResult(boolean live, BigDecimal score) {
    }

    public record BvnResult(boolean matched, BigDecimal confidenceScore) {
    }

    private final RestClient client;
    private final String appId;
    private final String secretKey;
    private final BigDecimal minimumConfidence;

    public DojahIdentityProvider(
            @Value("${account.verification.dojah.base-url:https://sandbox.dojah.io}") String baseUrl,
            @Value("${account.verification.dojah.app-id:}") String appId,
            @Value("${account.verification.dojah.secret-key:}") String secretKey,
            @Value("${account.verification.minimum-confidence:90}") BigDecimal minimumConfidence) {
        this.appId = appId;
        this.secretKey = secretKey;
        if (minimumConfidence.compareTo(BigDecimal.valueOf(50)) < 0
                || minimumConfidence.compareTo(BigDecimal.valueOf(100)) > 0
                || minimumConfidence.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Dojah minimum confidence must be between 50 and 100");
        }
        URI providerUri = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(providerUri.getScheme())
                || !("sandbox.dojah.io".equalsIgnoreCase(providerUri.getHost())
                || "api.dojah.io".equalsIgnoreCase(providerUri.getHost()))
                || providerUri.getPort() != -1 || providerUri.getRawPath() != null && !providerUri.getRawPath().isEmpty()
                || providerUri.getRawQuery() != null || providerUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Dojah base URL must be the official sandbox or production endpoint");
        }
        this.minimumConfidence = minimumConfidence;

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public DocumentResult verifyDocument(String documentImage, String selfieImage) {
        JsonNode response = execute(client.post()
                .uri("/api/v1/kyc/photoid/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::setCredentials)
                .body(new DocumentCheckRequest(documentImage, selfieImage))
                .retrieve());

        JsonNode result = response.path("entity").path("selfie");
        if (result.isMissingNode() || !result.hasNonNull("match")
                || !result.hasNonNull("confidence_value") || !result.hasNonNull("card_type")) {
            throw new DojahProviderUnavailableException();
        }
        BigDecimal score = decimal(result.get("confidence_value"));
        return new DocumentResult(result.path("match").asBoolean(false)
                && score.compareTo(minimumConfidence) >= 0, score, result.path("card_type").asText());
    }

    public LivenessResult checkLiveness(String selfieImage) {
        JsonNode response = execute(client.post()
                .uri("/api/v1/ml/liveness")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::setCredentials)
                .body(new LivenessRequest(selfieImage))
                .retrieve());

        JsonNode entity = response.path("entity");
        JsonNode face = entity.path("face");
        JsonNode liveness = entity.path("liveness");
        if (!face.hasNonNull("face_detected") || !face.hasNonNull("multiface_detected")
                || !liveness.hasNonNull("liveness_check") || !liveness.hasNonNull("liveness_probability")) {
            throw new DojahProviderUnavailableException();
        }
        BigDecimal score = decimal(liveness.get("liveness_probability"));
        return new LivenessResult(face.path("face_detected").asBoolean(false)
                && !face.path("multiface_detected").asBoolean(true)
                && liveness.path("liveness_check").asBoolean(false)
                && score.compareTo(minimumConfidence) >= 0, score);
    }

    public SelfieResult verifyBvnSelfie(String bvn, String selfieImage) {
        JsonNode response = execute(client.post()
                .uri("/api/v1/kyc/bvn/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::setCredentials)
                .body(new BvnSelfieRequest(bvn, selfieImage, minimumConfidence.intValueExact()))
                .retrieve());
        JsonNode result = response.path("entity").path("selfie_verification");
        if (!result.hasNonNull("match") || !result.hasNonNull("confidence_value")) {
            throw new DojahProviderUnavailableException();
        }
        BigDecimal score = decimal(result.get("confidence_value"));
        return new SelfieResult(result.path("match").asBoolean(false)
                && score.compareTo(minimumConfidence) >= 0, score);
    }

    public List<String> readAddressDocument(String image) {
        JsonNode response = execute(client.post()
                .uri("/api/v1/ml/ocr/generic")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::setCredentials)
                .body(new OcrRequest(image))
                .retrieve());
        JsonNode lines = response.path("entity").path("data");
        if (!lines.isArray()) {
            throw new DojahProviderUnavailableException();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode line : lines) {
            if (line.isTextual()) {
                result.add(line.asText());
            }
        }
        if (result.isEmpty()) {
            throw new AccountOperationException("The address document could not be read");
        }
        return List.copyOf(result);
    }

    public BvnResult validateBvn(String bvn, String firstName, String lastName) {
        JsonNode response = execute(client.get()
                .uri(uri -> uri.path("/api/v1/kyc/bvn")
                        .queryParam("bvn", bvn)
                        .queryParam("first_name", firstName)
                        .queryParam("last_name", lastName)
                        .build())
                .headers(this::setCredentials)
                .retrieve());

        JsonNode entity = response.path("entity");
        if (entity.isMissingNode()) {
            throw new DojahProviderUnavailableException();
        }
        JsonNode first = entity.path("first_name");
        JsonNode last = entity.path("last_name");
        JsonNode bvnResult = entity.path("bvn");
        if (!first.hasNonNull("status") || !last.hasNonNull("status")
                || !bvnResult.hasNonNull("status")
                || !first.hasNonNull("confidence_value") || !last.hasNonNull("confidence_value")) {
            throw new DojahProviderUnavailableException();
        }

        BigDecimal firstScore = decimal(first.get("confidence_value"));
        BigDecimal lastScore = decimal(last.get("confidence_value"));
        BigDecimal score = firstScore.min(lastScore);
        boolean matched = bvnResult.path("status").asBoolean(false)
                && first.path("status").asBoolean(false)
                && last.path("status").asBoolean(false)
                && score.compareTo(minimumConfidence) >= 0;
        return new BvnResult(matched, score);
    }

    private void setCredentials(org.springframework.http.HttpHeaders headers) {
        if (appId == null || appId.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new DojahProviderUnavailableException();
        }
        headers.set("AppId", appId);
        headers.set("Authorization", secretKey);
    }

    private static JsonNode execute(ResponseSpec response) {
        try {
            JsonNode body = response.body(JsonNode.class);
            if (body == null) {
                throw new DojahProviderUnavailableException();
            }
            return body;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                throw new AccountOperationException("Dojah could not verify the supplied identity details");
            }
            throw new DojahProviderUnavailableException();
        } catch (RestClientException e) {
            throw new DojahProviderUnavailableException();
        }
    }

    private static BigDecimal decimal(JsonNode value) {
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException | NullPointerException e) {
            throw new DojahProviderUnavailableException();
        }
    }

    private record DocumentCheckRequest(String photoid_image, String selfie_image) {
    }

    private record LivenessRequest(String image) {
    }

    private record BvnSelfieRequest(String bvn, String selfie_image, int threshold) {
    }

    private record OcrRequest(String img) {
    }
}
