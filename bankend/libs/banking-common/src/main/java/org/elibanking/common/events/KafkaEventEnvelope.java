package org.elibanking.common.events;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.elibanking.common.web.RequestIdContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Shared producer and compatibility helpers for the versioned domain-event envelope. */
public final class KafkaEventEnvelope {
    private KafkaEventEnvelope() {}

    public static Map<String, Object> wrap(String type, Map<String, ?> payload, String correlationId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("correlationId", correlationId);
        Map<String, Object> body = new LinkedHashMap<>();
        payload.forEach(body::put);
        envelope.put("payload", body);
        return envelope;
    }

    public static ProducerRecord<String, Object> record(String topic, String key, Map<String, ?> payload) {
        Map<String, Object> envelope = wrap(topic, payload, RequestIdContext.current().orElse(null));
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, envelope);
        KafkaEventHeaders.apply(envelope, (name, value) -> record.headers().add(name, value));
        return record;
    }

    /** Accepts the shared envelope and the previous flat event maps during the compatibility window. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> payload(Map<String, Object> event) {
        Object nested = event.get("payload");
        if (nested instanceof Map<?, ?> map) return (Map<String, Object>) map;
        Map<String, Object> legacy = new LinkedHashMap<>(event);
        legacy.keySet().removeAll(java.util.Set.of("eventId", "type", "version", "occurredAt", "correlationId"));
        return legacy;
    }

    public static String correlationId(Map<String, Object> event) {
        Object value = event.get("correlationId");
        return value == null ? null : value.toString();
    }

    public static void withContext(Map<String, Object> event, Runnable action) {
        RequestIdContext.withId(correlationId(event), action);
    }
}
