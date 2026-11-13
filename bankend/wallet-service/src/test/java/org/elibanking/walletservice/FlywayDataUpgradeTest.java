package org.elibanking.walletservice;

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

/** Verifies persisted PIN credentials survive the wallet schema upgrades. */
@Testcontainers(disabledWithoutDocker = true)
class FlywayDataUpgradeTest {

    @Container
    static final MySQLContainer mysql = TestImages.mysql();

    @Test
    void v1PinCredentialSurvivesUpgradeToLatest() throws SQLException {
        String database = "upgrade" + UUID.randomUUID().toString().replace("-", "");
        String url = url(database);
        try {
            execute(mysql.getJdbcUrl(), "CREATE DATABASE `" + database + "`");
            migrate(url, "1");
            try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                 var statement = connection.prepareStatement(
                         "INSERT INTO pin_credential (user_id, pin_hash) VALUES ('upgrade-user', 'argon-hash')")) {
                statement.executeUpdate();
            }

            Flyway latest = migrate(url, null);
            assertEquals("7", latest.info().current().getVersion().getVersion());
            try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT pin_hash, pepper_version, failed_count FROM pin_credential WHERE user_id='upgrade-user'")) {
                result.next();
                assertEquals("argon-hash", result.getString("pin_hash"));
                assertEquals(1, result.getInt("pepper_version"));
                assertEquals(0, result.getInt("failed_count"));
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
