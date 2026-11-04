package org.elibanking.accountservice.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/**
 * The outbox relay sends the envelope as the raw JSON text it was stored as. The application's default producer encodes
 * values as JSON, which would quote it, so the relay gets its own producer with plain string serializers.
 * Idempotent, acknowledged by all replicas: a retried send does not duplicate a record.
 */
@Configuration
@EnableScheduling
public class OutboxKafkaConfig {

    /**
     * The bootstrap servers come from Boot's connection details, not from the property. In tests the Kafka container
     * supplies them through {@code @ServiceConnection}, and a plain property would still point at localhost:9092.
     */
    @Bean
    KafkaTemplate<String, String> outboxKafkaTemplate(KafkaConnectionDetails connection) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", connection.getBootstrapServers()),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
