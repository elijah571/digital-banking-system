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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Verifies and durably records Paystack payout webhook triggers; wallet independently verifies provider status before acting. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaystackWebhookService {

    static final String CONSUMER = "paystack-payout-webhook";
    static final String SUCCESS_TOPIC = "payment.payout.success";
    static final String FAILED_TOPIC = "payment.payout.failed";
    static final String REVERSED_TOPIC = "payment.payout.reversed";

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    @Value("${payout.paystack.secret-key:}")
    private String secretKey;

    public void verifySignature(String rawBody, String signature) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Paystack webhook is not configured");
        }
        if (rawBody == null || signature == null || !signature.matches("(?i)[0-9a-f]{128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Paystack signature");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] supplied = signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Paystack signature");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Paystack signature could not be verified", e);
        }
    }

    @Transactional
    public void record(String rawBody) {
        JsonNode root = mapper.readTree(rawBody);
        String event = root.path("event").asText();
        String topic = switch (event) {
            case "transfer.success" -> SUCCESS_TOPIC;
            case "transfer.failed" -> FAILED_TOPIC;
            case "transfer.reversed" -> REVERSED_TOPIC;
            default -> null;
        };
        if (topic == null) {
            return;
        }
        JsonNode data = root.path("data");
        String reference = data.path("reference").asText();
        final String payoutKey;
        try {
            payoutKey = PaystackReference.toPayoutKey(reference);
        } catch (IllegalArgumentException notOurs) {
            log.info("Ignoring Paystack {} event for a reference outside this payout namespace", event);
            return;
        }
        String eventId = event + ":" + reference;
        try {
            jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)", CONSUMER, eventId);
        } catch (DuplicateKeyException duplicate) {
            log.info("Paystack event {} was already recorded", eventId);
            return;
        } catch (DataAccessException failure) {
            throw new WebhookNotRecordedException(eventId, failure);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("providerReference", reference);
        payload.put("payoutKey", payoutKey);
        payload.put("providerEvent", event);
        payload.put("amountKobo", data.path("amount").isNumber() ? data.path("amount").longValue() : null);
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
        log.info("Paystack {} event recorded for payout {}", event, payoutKey);
    }
}
