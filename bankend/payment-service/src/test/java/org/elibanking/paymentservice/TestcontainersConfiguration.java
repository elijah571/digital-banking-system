package org.elibanking.paymentservice;

import org.elibanking.common.testsupport.TestImages;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

/** Real MySQL, Kafka in Docker for integration tests (same versions as compose.yaml). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return TestImages.mysql();
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return TestImages.kafka();
    }

    /**
     * Scheduling is on, so the relay would run in every context. It stays off here: a test that checks the relay calls it
     * itself, so the rows it sees are the ones that test wrote.
     */
    @Bean
    DynamicPropertyRegistrar relayOff() {
        return registry -> registry.add("outbox.relay.enabled", () -> "false");
    }
}
