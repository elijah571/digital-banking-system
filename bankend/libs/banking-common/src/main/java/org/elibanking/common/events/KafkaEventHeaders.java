package org.elibanking.common.events;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

/** Adds stable event and request correlation metadata to Kafka record headers. */
public final class KafkaEventHeaders {

    private KafkaEventHeaders() {
    }

    public static void apply(Map<?, ?> envelope, BiConsumer<String, byte[]> addHeader) {
        add(envelope, "eventId", "eventId", addHeader);
        add(envelope, "correlationId", "X-Request-Id", addHeader);
    }

    private static void add(Map<?, ?> envelope, String key, String header, BiConsumer<String, byte[]> addHeader) {
        Object value = envelope.get(key);
        if (value == null || value.toString().isBlank()) {
            return;
        }
        addHeader.accept(header, value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
