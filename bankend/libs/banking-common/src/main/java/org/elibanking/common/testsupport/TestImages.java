package org.elibanking.common.testsupport;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test containers on the same image versions as compose.yaml (keep both in sync), so integration tests run on the
 * real MySQL / Kafka / Redis used in dev and production. Declare them as {@code @ServiceConnection} beans:
 * <pre>
 * &#64;Bean &#64;ServiceConnection MySQLContainer mysql() { return TestImages.mysql(); }
 * </pre>
 */
public final class TestImages {

    public static final String MYSQL = "mysql:26.10";
    public static final String KAFKA = "apache/kafka:4.3.1";
    public static final String REDIS = "redis:8.10.2";

    private TestImages() {
    }

    public static MySQLContainer mysql() {
        // Same server settings as compose.yaml
        return new MySQLContainer(DockerImageName.parse(MYSQL))
                .withCommand("--log-bin-trust-function-creators=1");
    }

    public static KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse(KAFKA));
    }

    /** Use with {@code @ServiceConnection(name = "redis")}. */
    public static GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse(REDIS)).withExposedPorts(6379);
    }
}
