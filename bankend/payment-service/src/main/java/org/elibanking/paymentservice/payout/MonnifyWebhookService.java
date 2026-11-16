package org.elibanking.paymentservice.payout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.elibanking.paymentservice.outbox.OutboxWriter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Monnify callbacks are authenticated, deduplicated triggers; wallet verifies provider status before posting ledger entries. */
@Service
public class MonnifyWebhookService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OutboxWriter outbox;
    private final String secret;

    public MonnifyWebhookService(JdbcTemplate jdbc, ObjectMapper mapper, OutboxWriter outbox,
                                 @Value("${payout.monnify.webhook-secret:}") String secret) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.outbox = outbox;
        this.secret = secret;
    }

    @Transactional
    public void record(String rawBody, String signature) {
        verify(rawBody, signature);
        JsonNode root = mapper.readTree(rawBody);
        JsonNode data = root.path("eventData").isMissingNode() ? root.path("data") : root.path("eventData");
        String reference = firstText(data, "reference", "transactionReference", "paymentReference");
        String key;
        try { key = PayoutReference.toPayoutKey(reference); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Monnify event has an invalid payout reference"); }
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM payout_dispatch WHERE payout_key=?", Integer.class, key);
        if (found == null || found == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Monnify event does not match a known payout");
        }
        String eventId = firstText(root, "eventId", "id");
        if (eventId == null || eventId.isBlank()) eventId = root.path("eventType").asText("unknown") + ":" + reference;
        try {
            jdbc.update("INSERT INTO processed_event (consumer,event_id) VALUES ('monnify-payout-webhook',?)", eventId);
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payoutKey", key);
        payload.put("providerName", "monnify");
        payload.put("providerReference", reference);
        payload.put("providerEvent", root.path("eventType").asText(""));
        payload.put("amount", data.path("amount").isNumber() ? data.path("amount").decimalValue() : data.path("amount").asText(null));
        payload.put("currency", data.path("currency").asText(null));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", "monnify:" + eventId);
        event.put("type", "payment.payout.updated");
        event.put("version", 1);
        event.put("occurredAt", Instant.now().toString());
        event.put("payload", payload);
        outbox.append("payment.payout.updated", key, key, event);
    }

    private void verify(String body, String supplied) {
        if (secret == null || secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Monnify webhook is not configured");
        if (supplied == null || !supplied.matches("(?i)^[0-9a-f]{128}$"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing or invalid Monnify signature");
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, supplied.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Monnify signature");
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("could not verify Monnify signature", e); }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) if (node.hasNonNull(field) && !node.path(field).asText().isBlank()) return node.path(field).asText();
        return null;
    }
}
