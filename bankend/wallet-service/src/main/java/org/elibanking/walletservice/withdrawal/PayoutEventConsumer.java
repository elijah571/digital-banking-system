package org.elibanking.walletservice.withdrawal;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.walletservice.outbox.OutboxWriter;
import org.elibanking.walletservice.payout.OutboundPayoutRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider webhook messages are triggers only; the processor asks the provider for the authoritative status. */
@Slf4j
@Component
public class PayoutEventConsumer {

    static final String CONSUMER = "wallet-payout";
    static final String ALERT_TOPIC = "wallet.payout.alert";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OutboundPayoutRepository payouts;
    private final PayoutProcessor processor;
    private final OutboxWriter outbox;

    public PayoutEventConsumer(JdbcTemplate jdbc, ObjectMapper mapper, OutboundPayoutRepository payouts,
                               PayoutProcessor processor, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.payouts = payouts;
        this.processor = processor;
        this.outbox = outbox;
    }

    @KafkaListener(topics = {"payment.payout.success", "payment.payout.failed", "payment.payout.reversed"},
            groupId = "wallet-service-group")
    @Transactional
    public void receive(String message) {
        JsonNode event = mapper.readTree(message);
        String eventId = event.path("eventId").asText();
        String payoutKey = event.path("payload").path("payoutKey").asText();
        String type = event.path("type").asText();
        if (eventId.isBlank() || !payoutKey.matches("out-[1-9][0-9]*")) {
            throw new IllegalArgumentException("payout event is missing its event id or payout key");
        }
        try {
            jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)", CONSUMER, eventId);
        } catch (DuplicateKeyException duplicate) {
            log.info("payout event {} was already applied", eventId);
            return;
        }
        long id = Long.parseLong(payoutKey.substring(4));
        if (payouts.findById(id).isEmpty()) {
            alert(payoutKey, eventId, "payout event references a payout not found in wallet-service");
            return;
        }
        try {
            if ("payment.payout.reversed".equals(type) && processor.reverseIfReversed(id)) {
                return;
            }
            processor.advance(id);
        } catch (PayoutProcessor.ProviderStatusMismatchException mismatch) {
            alert(payoutKey, eventId, mismatch.getMessage());
        }
    }

    private void alert(String payoutKey, String eventId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payoutKey", payoutKey);
        payload.put("eventId", eventId);
        payload.put("reason", reason);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", "alert:" + eventId);
        envelope.put("type", ALERT_TOPIC);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        outbox.append(ALERT_TOPIC, payoutKey, payoutKey, envelope);
    }
}
