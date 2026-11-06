package org.elibanking.walletservice.deposit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elibanking.common.testsupport.TestImages;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.PostResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Stripe outcome as payment-service's outbox relay sends it, through a real broker. The listener reads the raw JSON and applies
 * it once (wallet-plan 3.8). The ledger is mocked.
 */
@SpringBootTest(properties = "outbox.relay.enabled=false")
@Import({TestcontainersConfiguration.class, DepositEventKafkaTest.Broker.class})
class DepositEventKafkaTest {

    @Autowired
    DepositRepository deposits;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    /** The broker this test starts. The spring.kafka property is the dev default (localhost:9092), so it is not used here. */
    @Autowired
    KafkaContainer broker;

    @MockitoBean
    AccountServiceClient accounts;

    @Test
    void aStripeOutcomeOnTheTopicIsAppliedOnce() throws Exception {
        Deposit deposit = Deposit.pending(UUID.randomUUID().toString(), "user-" + UUID.randomUUID(),
                String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L)),
                new BigDecimal("20000.00"), "NGN", Instant.now().plus(Duration.ofHours(24)));
        deposits.save(deposit);
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));
        String evt = "evt_" + UUID.randomUUID();
        String json = "{\"eventId\":\"" + evt + "\",\"type\":\"payment.intent.succeeded\",\"version\":1,"
                + "\"occurredAt\":\"2026-11-06T00:00:00Z\",\"payload\":{\"stripeEventId\":\"" + evt + "\","
                + "\"paymentIntentId\":\"pi_kafka\",\"depositId\":\"" + deposit.getId() + "\","
                + "\"amountReceived\":\"20000.00\",\"currency\":\"NGN\"}}";

        // payment-service creates this topic in production (DepositTopics). The test creates it the same way, before the listener starts.
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("payment.intent.succeeded", 1, (short) 1))).all().get();
        }
        // The listeners are off in every test context (TestcontainersConfiguration). This test starts them on its own broker.
        listeners.start();
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>("payment.intent.succeeded", deposit.getId(), json)).get();
        }

        long deadline = System.currentTimeMillis() + 60_000;
        while (!"COMPLETED".equals(statusOf(deposit.getId())) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals("COMPLETED", statusOf(deposit.getId()));
        verify(accounts, times(1)).postEntry(eq("DEPOSIT_CREDIT"), eq("dep-" + deposit.getId() + "-credit"),
                anyString(), anyString(), anyList());
    }

    private String statusOf(String depositId) {
        return jdbc.queryForObject("SELECT status FROM deposit WHERE id = ?", String.class, depositId);
    }

    static class Broker {
        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return TestImages.kafka();
        }
    }
}
