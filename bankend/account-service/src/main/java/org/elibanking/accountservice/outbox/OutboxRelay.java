package org.elibanking.accountservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes unsent outbox rows to Kafka (wallet-plan 3.8). Rows are locked with {@code FOR UPDATE SKIP LOCKED}, so several
 * relays can run side by side without publishing a row twice. A row is marked sent only after Kafka acknowledged it. If a
 * send fails, the batch rolls back and its rows stay unsent, so they are retried. Consumers deduplicate on the event id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> outboxKafkaTemplate, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.kafka = outboxKafkaTemplate;
        this.tx = tx;
    }

    /**
     * Publishes up to {@code batchSize} unsent rows in one transaction. A row that fails is counted in {@code attempts}
     * and left unsent, and the batch carries on, so one bad row cannot hold back the rows behind it.
     *
     * @return how many rows were published in this pass
     */
    @Transactional
    public int publishPending(int batchSize) {
        List<Pending> rows = jdbc.query("""
                        SELECT id, topic, aggregate_id, payload FROM outbox
                        WHERE sent_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                        """,
                (rs, n) -> new Pending(rs.getLong("id"), rs.getString("topic"), rs.getString("aggregate_id"),
                        rs.getString("payload")),
                batchSize);
        int published = 0;
        for (Pending row : rows) {
            try {
                send(row);
                jdbc.update("UPDATE outbox SET sent_at = CURRENT_TIMESTAMP(6), attempts = attempts + 1 WHERE id = ?",
                        row.id());
                published++;
            } catch (RuntimeException e) {
                jdbc.update("UPDATE outbox SET attempts = attempts + 1 WHERE id = ?", row.id());
                log.warn("outbox event {} not published, will retry: {}", row.id(), e.getMessage());
            }
        }
        return published;
    }

    /**
     * The scheduled pass. It opens its own transaction explicitly. Calling {@link #publishPending} on {@code this} would skip
     * the {@code @Transactional} proxy, so the row locks would be released right after the SELECT, and concurrent passes
     * would then send the same rows. The test {@code concurrentPassesOnTheScheduledAndDirectPathsNeverDeliverAnEventTwice}
     * reproduced that before this change.
     */
    @Scheduled(fixedDelayString = "${outbox.relay.delay-ms:1000}")
    public void relay() {
        try {
            tx.executeWithoutResult(status -> publishPending(100));
        } catch (RuntimeException e) {
            log.warn("outbox relay pass failed; unsent rows stay queued: {}", e.getMessage());
        }
    }

    private void send(Pending row) {
        try {
            kafka.send(row.topic(), row.aggregateId(), row.payload()).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing outbox event " + row.id(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish outbox event " + row.id(), e);
        }
    }

    private record Pending(long id, String topic, String aggregateId, String payload) {
    }
}
