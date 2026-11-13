package org.elibanking.authservice;

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

/** Existing customer rows remain readable after later auth migrations. */
@Testcontainers(disabledWithoutDocker = true)
class FlywayDataUpgradeTest {

    @Container
    static final MySQLContainer mysql = TestImages.mysql();

    @Test
    void v1UserSurvivesUpgradeToLatest() throws SQLException {
        String database = "upgrade" + UUID.randomUUID().toString().replace("-", "");
        String url = url(database);
        try {
            execute(mysql.getJdbcUrl(), "CREATE DATABASE `" + database + "`");
            migrate(url, "1");
            try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                 var statement = connection.prepareStatement("""
                         INSERT INTO users (id, created_at, email, email_verified, enabled, full_name, password_hash, role, updated_at)
                         VALUES ('upgrade-user', NOW(6), 'upgrade@example.test', b'1', b'1', 'Upgrade User', 'encoded', 'USER', NOW(6))
                         """)) {
                statement.executeUpdate();
            }

            Flyway latest = migrate(url, null);
            assertEquals("2", latest.info().current().getVersion().getVersion());
            try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT email, role FROM users WHERE id='upgrade-user'")) {
                result.next();
                assertEquals("upgrade@example.test", result.getString("email"));
                assertEquals("USER", result.getString("role"));
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
