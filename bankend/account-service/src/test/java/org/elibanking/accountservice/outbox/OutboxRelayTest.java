package org.elibanking.accountservice.outbox;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elibanking.accountservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxRelayTest {

    @Autowired
    OutboxWriter outbox;

    @Autowired
    OutboxRelay relay;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaContainer kafka;

    @Test
    void eachEventIsDeliveredOnceAndThenMarkedSent() {
        String topic = "relay.test." + UUID.randomUUID();
        String aggregate = UUID.randomUUID().toString();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(topic));
            String eventId = tx.execute(status -> outbox.append(topic, "TEST_EVENT", aggregate, null, "{\"n\":1}"));

            // The scheduled relay may publish the row before this call. Either way it must go out exactly once.
            relay.publishPending(100);

            List<ConsumerRecord<String, String>> delivered = pollFor(consumer, aggregate, 1);
            assertEquals(1, delivered.size(), "one record for the event, never two");
            // MySQL normalises JSON text (spaces after colons), so match the id, not the exact bytes.
            assertTrue(delivered.getFirst().value().contains(eventId),
                    "the value is the envelope as stored: " + delivered.getFirst().value());
            assertEquals(aggregate, delivered.getFirst().key());

            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND sent_at IS NULL", Long.class, aggregate));
        }
    }

    @Test
    void theRelayDoesNotDuplicateEventsAcrossPasses() {
        String topic = "relay.test." + UUID.randomUUID();
        String aggregate = UUID.randomUUID().toString();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(topic));
            tx.executeWithoutResult(status -> {
                outbox.append(topic, "TEST_EVENT", aggregate, null, "{\"n\":1}");
                outbox.append(topic, "TEST_EVENT", aggregate, null, "{\"n\":2}");
            });

            relay.publishPending(1);
            relay.publishPending(1);
            relay.publishPending(100);

            List<ConsumerRecord<String, String>> delivered = pollFor(consumer, aggregate, 2);
            assertEquals(2, delivered.size(), "two events, each delivered once");
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND sent_at IS NULL", Long.class, aggregate));
        }
    }

    /**
     * The scheduled path and direct calls race on the same rows. Every event must still be delivered exactly once.
     */
    @Test
    void concurrentPassesOnTheScheduledAndDirectPathsNeverDeliverAnEventTwice() throws Exception {
        String topic = "relay.test." + UUID.randomUUID();
        String aggregate = UUID.randomUUID().toString();
        int events = 20;

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(topic));
            tx.executeWithoutResult(status -> {
                for (int i = 0; i < events; i++) {
                    outbox.append(topic, "TEST_EVENT", aggregate, null, "{\"n\":" + i + "}");
                }
            });

            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> passes = new ArrayList<>();
                for (int thread = 0; thread < 8; thread++) {
                    boolean scheduledPath = thread % 2 == 0;
                    passes.add(pool.submit(() -> {
                        for (int round = 0; round < 10; round++) {
                            if (scheduledPath) {
                                relay.relay();
                            } else {
                                relay.publishPending(3);
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> pass : passes) {
                    pass.get(120, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            List<ConsumerRecord<String, String>> delivered = pollFor(consumer, aggregate, events);
            assertEquals(events, delivered.size(), "each event delivered exactly once");
            assertEquals(events, delivered.stream().map(ConsumerRecord::value).distinct().count(),
                    "no envelope was delivered twice");
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND sent_at IS NULL", Long.class, aggregate));
        }
    }

    // ------------------------------------------------------------------ helpers

    private KafkaConsumer<String, String> consumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    /** Polls until {@code expected} records for the aggregate arrive, or fails after a deadline. */
    private static List<ConsumerRecord<String, String>> pollFor(KafkaConsumer<String, String> consumer, String aggregate,
                                                               int expected) {
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && matched.size() < expected) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (aggregate.equals(record.key())) {
                    matched.add(record);
                }
            }
        }
        // one extra short poll, to catch a duplicate that would arrive late
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(2))) {
            if (aggregate.equals(record.key())) {
                matched.add(record);
            }
        }
        return matched;
    }
}
