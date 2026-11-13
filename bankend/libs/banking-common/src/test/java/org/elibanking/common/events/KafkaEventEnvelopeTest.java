package org.elibanking.common.events;

import org.elibanking.common.web.RequestIdContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KafkaEventEnvelopeTest {
    @Test
    void recordCarriesVersionedEnvelopeAndCorrelationHeaders() {
        RequestIdContext.withId("request-42", () -> {
            var record = KafkaEventEnvelope.record("transaction.completed", "tx-1", Map.of("transactionId", "tx-1"));
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) record.value();
            assertNotNull(envelope.get("eventId"));
            assertEquals("transaction.completed", envelope.get("type"));
            assertEquals(1, envelope.get("version"));
            assertEquals("request-42", envelope.get("correlationId"));
            assertEquals("tx-1", KafkaEventEnvelope.payload(envelope).get("transactionId"));
            assertEquals("request-42", new String(record.headers().lastHeader("X-Request-Id").value(), StandardCharsets.UTF_8));
            assertEquals(envelope.get("eventId"), new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8));
        });
    }

    @Test
    void legacyFlatEventsRemainReadable() {
        assertEquals(Map.of("transactionId", "tx-1", "amount", "10.00"),
                KafkaEventEnvelope.payload(Map.of("eventId", "old-1", "type", "legacy", "transactionId", "tx-1",
                        "amount", "10.00")));
    }
}
