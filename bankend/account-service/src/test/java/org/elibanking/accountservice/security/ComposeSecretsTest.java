package org.elibanking.accountservice.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compose files decide which containers get INTERNAL_JWT_SECRET, which signs the service tokens (wallet-plan 3.7).
 * The notification service never mints or checks one, so it must not get the secret. The test reads the files, so a
 * later edit that adds it fails the build.
 */
class ComposeSecretsTest {

    private static final String SECRET = "INTERNAL_JWT_SECRET";
    private static final String BASE = "../compose.yaml";
    private static final String PROD = "../compose.prod.yaml";

    @Test
    void notificationServiceNeverGetsTheServiceTokenSecret() throws IOException {
        for (String file : List.of(BASE, PROD)) {
            assertFalse(environment(file, "notification-service").containsKey(SECRET),
                    file + ": notification-service must not get " + SECRET);
        }
    }

    @Test
    void theServicesThatSignOrCheckServiceTokensDoGetTheSecret() throws IOException {
        for (String service : List.of("account-service", "transaction-service", "payment-service",
                "fraud-detection-service")) {
            assertTrue(environment(BASE, service).containsKey(SECRET), service + " needs " + SECRET);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(String file, String service) throws IOException {
        Map<String, Object> doc = load(file);
        Map<String, Object> services = (Map<String, Object>) doc.get("services");
        Map<String, Object> definition = (Map<String, Object>) services.get(service);
        Object environment = definition.get("environment");
        return environment == null ? Map.of() : (Map<String, Object>) environment;
    }

    /**
     * The compose files use two compose-only tags, {@code !reset} and {@code !override}, which SnakeYAML does not know.
     * They only change how a value merges, and the test reads environments as they are written, so the tags are dropped
     * before the file is parsed. Anchors and {@code <<:} merges are still resolved.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String file) throws IOException {
        String text = Files.readString(Path.of(file));
        String plain = text.replaceAll("(?m)(^|\\s)!(override|reset)(?=\\s|$)", "$1");
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(plain);
    }
}
