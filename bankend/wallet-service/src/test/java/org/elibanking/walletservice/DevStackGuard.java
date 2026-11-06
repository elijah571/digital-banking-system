package org.elibanking.walletservice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stops tests from reaching the dev stack. The dev stack publishes Kafka (9092), MySQL (3306) and Redis (6379) on fixed
 * host ports. Tests must use Testcontainers, which use random ports, so any address on one of those ports is a
 * configuration bug, and the test run stops before it can write anything.
 */
public final class DevStackGuard {

    static final Map<Integer, String> DEV_STACK_PORTS = Map.of(9092, "Kafka", 3306, "MySQL", 6379, "Redis");

    private static final Pattern JDBC_HOST_PORT = Pattern.compile("^jdbc:mysql://([^/?,]+)");

    private DevStackGuard() {
    }

    /**
     * @param what    what the address configures, for the message
     * @param address one or more {@code host:port} pairs, comma-separated
     * @return the problems found; empty if none
     */
    public static List<String> problems(String what, String address) {
        List<String> problems = new ArrayList<>();
        if (address == null) {
            return problems;
        }
        for (String endpoint : address.split(",")) {
            String trimmed = endpoint.trim();
            int colon = trimmed.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(trimmed.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            String service = DEV_STACK_PORTS.get(port);
            if (service != null) {
                problems.add(what + " points at the dev stack's " + service + " port: " + trimmed);
            }
        }
        return problems;
    }

    /** The {@code host:port} part of a MySQL JDBC URL, or an empty string if the URL has none. */
    public static String hostPortOfJdbcUrl(String jdbcUrl) {
        Matcher matcher = JDBC_HOST_PORT.matcher(jdbcUrl == null ? "" : jdbcUrl);
        return matcher.find() ? matcher.group(1) : "";
    }
}
