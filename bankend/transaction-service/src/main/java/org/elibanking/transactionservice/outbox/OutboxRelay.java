package org.elibanking.transactionservice.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.elibanking.common.events.KafkaEventHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox events (wallet-plan 3.8). An event goes out only once its transfer is held (held_at is set), so a transfer
 * that is refused never reaches the fraud check. Rows are locked with SKIP LOCKED, so two relays never send the same row. A
 * row that fails stays unsent and is retried. Delivery is at least once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public void relay() {
        tx.executeWithoutResult(status -> publishPending());
    }

    /** Publishes the held, unsent events in one batch. Returns how many were sent. */
    public int publishPending() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.id, o.topic, o.message_key, o.payload
                FROM outbox o
                WHERE o.sent_at IS NULL
                  AND EXISTS (SELECT 1 FROM transactions t WHERE t.id = o.aggregate_id AND t.held_at IS NOT NULL)
                ORDER BY o.id
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, BATCH);
        int sent = 0;
        for (Map<String, Object> row : rows) {
            try {
                Object event = mapper.readValue(row.get("payload").toString(), Map.class);
                ProducerRecord<String, Object> record = new ProducerRecord<>(
                        (String) row.get("topic"), (String) row.get("message_key"), event);
                KafkaEventHeaders.apply((Map<?, ?>) event,
                        (name, value) -> record.headers().add(name, value));
                kafka.send(record).get(10, TimeUnit.SECONDS);
                jdbc.update("UPDATE outbox SET sent_at = NOW(6) WHERE id = ?", row.get("id"));
                sent++;
            } catch (Exception e) {
                // Keep the order: stop at the first failure, and retry from there on the next run.
                log.warn("Outbox row {} not sent, will retry: {}", row.get("id"), e.getMessage());
                break;
            }
        }
        return sent;
    }
}
