package org.elibanking.accountservice.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Appends an event to the {@code outbox} table in the business transaction (wallet-plan 3.8). A relay publishes it
 * later, so an event exists if and only if the change it describes committed. It must run inside a transaction
 * ({@code MANDATORY}).
 *
 * <p>Envelope (3.8): {@code {"eventId", "type", "version": 1, "occurredAt", "correlationId", "payload"}}.
 */
@Service
public class OutboxWriter {

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param payloadJson the event payload, as a JSON object or array (text)
     * @return the new event id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String append(String topic, String eventType, String aggregateId, String correlationId, String payloadJson) {
        String eventId = UUID.randomUUID().toString();
        String envelope = "{\"eventId\":" + JsonStrings.quote(eventId)
                + ",\"type\":" + JsonStrings.quote(eventType)
                + ",\"version\":1"
                + ",\"occurredAt\":" + JsonStrings.quote(Instant.now().toString())
                + ",\"correlationId\":" + (correlationId == null ? "null" : JsonStrings.quote(correlationId))
                + ",\"payload\":" + payloadJson
                + "}";
        jdbc.update("INSERT INTO outbox (event_id, topic, event_type, aggregate_id, payload) VALUES (?, ?, ?, ?, ?)",
                eventId, topic, eventType, aggregateId, envelope);
        return eventId;
    }
}
