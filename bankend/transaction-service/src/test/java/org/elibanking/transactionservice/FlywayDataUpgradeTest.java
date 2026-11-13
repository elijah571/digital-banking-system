package org.elibanking.transactionservice;

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

/** Verifies transaction data written at V1 remains readable after every later migration. */
@Testcontainers(disabledWithoutDocker = true)
class FlywayDataUpgradeTest {

    @Container
    static final MySQLContainer mysql = TestImages.mysql();

    @Test
    void v1TransactionSurvivesUpgradeToLatest() throws SQLException {
        String database = "upgrade" + UUID.randomUUID().toString().replace("-", "");
        String url = url(database);
        try {
            execute(mysql.getJdbcUrl(), "CREATE DATABASE `" + database + "`");
            migrate(url, "1");
            try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                 var statement = connection.prepareStatement("""
                         INSERT INTO transactions (id, amount, created_at, receiver_account_number, reference_number,
                                                   sender_account_number, status, type, sender_user_id)
                         VALUES ('upgrade-transaction', 25.00, NOW(6), '9000000002', 'upgrade-ref',
                                 '9000000001', 'COMPLETED', 'TRANSFER', 'upgrade-user')
                         """)) {
                statement.executeUpdate();
            }

            Flyway latest = migrate(url, null);
            assertEquals("6", latest.info().current().getVersion().getVersion());
            try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT amount, version FROM transactions WHERE id='upgrade-transaction'")) {
                result.next();
                assertEquals(0, result.getBigDecimal("amount").compareTo(new java.math.BigDecimal("25.00")));
                assertEquals(0L, result.getLong("version"));
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
