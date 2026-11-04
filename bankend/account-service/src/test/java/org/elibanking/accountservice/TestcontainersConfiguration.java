package org.elibanking.accountservice;

import org.elibanking.common.testsupport.TestImages;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import java.time.Duration;
import org.testcontainers.mysql.MySQLContainer;

/** Real MySQL, Kafka in Docker for integration tests (same versions as compose.yaml). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        // MySQL 9.7 on a loaded host can take longer than the default wait; a timeout here is not a test failure.
        return TestImages.mysql().withStartupTimeout(Duration.ofMinutes(3));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return TestImages.kafka();
    }
}
