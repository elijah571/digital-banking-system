package org.elibanking.walletservice;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.elibanking.common.testsupport.TestImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

/**
 * Real MySQL in Docker for integration tests (the same version as compose.yaml). One MySQL serves the whole test JVM, and
 * Testcontainers removes it when the JVM exits. Tests never use the dev stack: {@link DevStackGuard} stops the context if the
 * datasource is on a dev-stack port.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final MySQLContainer MYSQL = TestImages.mysql().withStartupTimeout(Duration.ofMinutes(3));
    private static final GenericContainer<?> REDIS = TestImages.redis();

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        startOnce(MYSQL);
        return MYSQL;
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        startOnce(REDIS);
        return REDIS;
    }

    private static synchronized void startOnce(GenericContainer<?> container) {
        if (!container.isRunning()) {
            container.start();
        }
    }

    /** Test values for the token secrets and the PIN secrets. Each is at least 32 bytes, as the application requires. */
    @Bean
    DynamicPropertyRegistrar testSecrets() {
        return registry -> {
            registry.add("auth.jwt-secret", () -> TestTokens.USER_SECRET);
            registry.add("internal-auth.jwt-secret", () -> TestTokens.SERVICE_SECRET);
            registry.add("pin.pepper", () -> "wallet-test-pepper-0123456789abcdefghij");
            registry.add("pin.otp-hmac-key", () -> "wallet-test-otp-hmac-key-0123456789abcdef");
        };
    }

    /** Runs first. If the datasource this context uses is on a dev-stack port, the context does not start. */
    @Bean
    DevStackGuardCheck devStackGuard(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            List<String> problems = DevStackGuard.problems("the datasource",
                    DevStackGuard.hostPortOfJdbcUrl(connection.getMetaData().getURL()));
            if (!problems.isEmpty()) {
                throw new IllegalStateException(String.join("; ", problems));
            }
        }
        return new DevStackGuardCheck();
    }

    /** A marker bean, so the guard runs as part of the context. */
    public static final class DevStackGuardCheck {
    }
}
