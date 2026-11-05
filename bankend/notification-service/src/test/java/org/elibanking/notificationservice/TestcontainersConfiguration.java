package org.elibanking.notificationservice;

import org.elibanking.common.testsupport.TestImages;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/** Real Kafka in Docker for integration tests (same versions as compose.yaml). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return TestImages.kafka();
    }
}
