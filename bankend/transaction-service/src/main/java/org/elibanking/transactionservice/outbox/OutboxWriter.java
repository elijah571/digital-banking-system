package org.elibanking.transactionservice.outbox;

import lombok.RequiredArgsConstructor;
import org.elibanking.common.web.RequestIdContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Appends an event to the outbox (wallet-plan 3.8). It runs only inside the business transaction it describes (MANDATORY), so the
 * event commits or rolls back together with the change, and {@link OutboxRelay} publishes it afterwards.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String messageKey, String aggregateId, Object event) {
        Map<String, Object> envelope = new LinkedHashMap<>(mapper.convertValue(event, Map.class));
        envelope.putIfAbsent("eventId", UUID.randomUUID().toString());
        envelope.putIfAbsent("type", topic);
        envelope.putIfAbsent("version", 1);
        envelope.putIfAbsent("occurredAt", Instant.now().toString());
        envelope.putIfAbsent("correlationId", RequestIdContext.current().orElse(null));
        jdbc.update("INSERT INTO outbox (aggregate_id, topic, message_key, payload) VALUES (?, ?, ?, ?)",
                aggregateId, topic, messageKey, mapper.writeValueAsString(envelope));
    }
}
