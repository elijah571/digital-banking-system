package org.elibanking.paymentservice;

import org.elibanking.common.testsupport.TestImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Existing payment rows remain readable after later payment migrations. */
@Testcontainers(disabledWithoutDocker = true)
class FlywayDataUpgradeTest {

    @Container
    static final MySQLContainer mysql = TestImages.mysql();

    @Test
    void v1PaymentSurvivesUpgradeToLatest() throws SQLException {
        String database = "upgrade" + UUID.randomUUID().toString().replace("-", "");
        String url = url(database);
        try {
            execute(mysql.getJdbcUrl(), "CREATE DATABASE `" + database + "`");
            migrate(url, "1");
            try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                 var statement = connection.prepareStatement("""
                         INSERT INTO payments (id, account_number, amount, created_at, currency, status, updated_at)
                         VALUES ('upgrade-payment', '9000000001', 80.00, NOW(6), 'NGN', 'CREATED', NOW(6))
                         """)) {
                statement.executeUpdate();
            }

            Flyway latest = migrate(url, null);
            assertEquals("3", latest.info().current().getVersion().getVersion());
            try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT amount, currency, status FROM payments WHERE id='upgrade-payment'")) {
                result.next();
                assertEquals(0, result.getBigDecimal("amount").compareTo(new java.math.BigDecimal("80.00")));
                assertEquals("NGN", result.getString("currency"));
                assertEquals("CREATED", result.getString("status"));
            }
        } finally {
            execute(mysql.getJdbcUrl(), "DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    private static Flyway migrate(String url, String target) {
        var config = Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration");
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        Flyway flyway = config.load();
        flyway.migrate();
        return flyway;
    }

    private static String url(String database) {
        return mysql.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
