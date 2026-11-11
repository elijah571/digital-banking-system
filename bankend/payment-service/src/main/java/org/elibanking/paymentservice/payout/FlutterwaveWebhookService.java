package org.elibanking.paymentservice.payout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.paymentservice.exception.WebhookNotRecordedException;
import org.elibanking.paymentservice.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies and durably records Flutterwave V3 payout webhook triggers (wallet-plan 9.6). The {@code verif-hash} header must equal the
 * dashboard secret hash (a constant-time equality check, not an HMAC of the body). The event is only a trigger: wallet-service
 * independently verifies the provider status before it moves any ledger money, so a late, duplicate or out-of-order event is harmless.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlutterwaveWebhookService {

    static final String CONSUMER = "flutterwave-payout-webhook";
    static final String SUCCESS_TOPIC = "payment.payout.success";
    static final String FAILED_TOPIC = "payment.payout.failed";
    static final String REVERSED_TOPIC = "payment.payout.reversed";

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    @Value("${payout.flutterwave.webhook-secret:}")
    private String webhookSecret;

    /** Flutterwave sends the dashboard secret hash in the {@code verif-hash} header; it is compared for equality, not as an HMAC. */
    public void verifySignature(String verifHash) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Flutterwave webhook is not configured");
        }
        if (verifHash == null || verifHash.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Flutterwave verification header");
        }
        byte[] expected = webhookSecret.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = verifHash.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Flutterwave signature");
        }
    }

    @Transactional
    public void record(String rawBody) {
        JsonNode root = mapper.readTree(rawBody);
        if (!"transfer.completed".equals(root.path("event").asText())) {
            return;
        }
        JsonNode data = root.path("data");
        String reference = data.path("reference").asText(null);
        String statusRaw = data.path("status").asText("");
        String topic = switch (statusRaw.toUpperCase(Locale.ROOT)) {
            case "SUCCESSFUL" -> SUCCESS_TOPIC;
            case "FAILED" -> FAILED_TOPIC;
            case "REVERSED" -> REVERSED_TOPIC;
            default -> null;
        };
        if (topic == null) {
            return;
        }
        final String payoutKey;
        try {
            payoutKey = PayoutReference.toPayoutKey(reference);
        } catch (IllegalArgumentException notOurs) {
            log.info("Ignoring Flutterwave transfer event for a reference outside this payout namespace");
            return;
        }
        String eventId = "transfer.completed:" + reference + ":" + statusRaw.toLowerCase(Locale.ROOT);
        try {
            jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)", CONSUMER, eventId);
        } catch (DuplicateKeyException duplicate) {
            log.info("Flutterwave event {} was already recorded", eventId);
            return;
        } catch (DataAccessException failure) {
            throw new WebhookNotRecordedException(eventId, failure);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("providerReference", reference);
        payload.put("payoutKey", payoutKey);
        payload.put("providerEvent", "transfer.completed");
        payload.put("providerStatus", statusRaw);
        payload.put("amount", data.path("amount").isNumber() ? data.path("amount").decimalValue() : null);
        payload.put("currency", data.path("currency").asText(null));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("type", topic);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        try {
            outbox.append(topic, payoutKey, payoutKey, envelope);
        } catch (RuntimeException failure) {
            throw new WebhookNotRecordedException(eventId, failure);
        }
        log.info("Flutterwave transfer.completed ({}) recorded for payout {}", statusRaw, payoutKey);
    }
}
