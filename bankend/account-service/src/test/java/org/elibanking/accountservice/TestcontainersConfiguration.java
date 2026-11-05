package org.elibanking.accountservice;

import javax.sql.DataSource;
import org.elibanking.common.testsupport.TestImages;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Real MySQL and Kafka in Docker for integration tests (same versions as compose.yaml).
 *
 * <p>One MySQL and one Kafka serve the whole test JVM. Every Spring context reuses them. Starting a fresh pair per context
 * overloaded a loaded host, and the containers timed out during startup. Testcontainers removes them when the JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // MySQL 9.7 and Kafka can take longer than the default wait on a loaded host; a timeout here is not a test failure.
    private static final MySQLContainer MYSQL = TestImages.mysql().withStartupTimeout(Duration.ofMinutes(3));
    private static final KafkaContainer KAFKA = TestImages.kafka().withStartupTimeout(Duration.ofMinutes(3));

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        startOnce(MYSQL);
        return MYSQL;
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        startOnce(KAFKA);
        return KAFKA;
    }

    private static synchronized void startOnce(GenericContainer<?> container) {
        if (!container.isRunning()) {
            container.start();
        }
    }

    /**
     * {@code @ServiceConnection} changes the connection details Boot uses, but not the plain property. Code that reads
     * {@code spring.kafka.bootstrap-servers} would still see the default, localhost:9092, which is the dev broker. So the
     * property is set to the container's address as well.
     */
    @Bean
    DynamicPropertyRegistrar kafkaBootstrapFromContainer(KafkaContainer kafka) {
        return registry -> {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
            // Test secrets for the two token kinds (TestTokens mints with the same values).
            registry.add("auth.jwt-secret", () -> TestTokens.USER_SECRET);
            registry.add("internal-auth.jwt-secret", () -> TestTokens.SERVICE_SECRET);
        };
    }

    /**
     * Runs before any test. If the Kafka, MySQL or Redis address the context uses is on a dev-stack port, the context does not
     * start, so every test that imports this configuration fails at once.
     */
    @Bean
    DevStackGuardCheck devStackGuard(Environment environment, DataSource dataSource, KafkaConnectionDetails kafka)
            throws SQLException {
        List<String> problems = new ArrayList<>();
        problems.addAll(DevStackGuard.problems("spring.kafka.bootstrap-servers",
                environment.getProperty("spring.kafka.bootstrap-servers")));
        problems.addAll(DevStackGuard.problems("KafkaConnectionDetails",
                String.join(",", kafka.getBootstrapServers())));
        String jdbcUrl;
        try (Connection connection = dataSource.getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
        }
        problems.addAll(DevStackGuard.problems("datasource URL", DevStackGuard.hostPortOfJdbcUrl(jdbcUrl)));
        problems.addAll(DevStackGuard.problems("spring.data.redis.port",
                environment.getProperty("spring.data.redis.port")));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Tests must use Testcontainers, not the dev stack: " + problems);
        }
        return new DevStackGuardCheck(List.of(jdbcUrl, String.join(",", kafka.getBootstrapServers())));
    }

    /** The addresses the guard checked, kept so the bean is not empty. */
    public record DevStackGuardCheck(List<String> checked) {
    }
}
