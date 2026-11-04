package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The backfill (V4) runs against a database that has the V1 schema and existing rows, which is the shape of a real
 * upgrade. Each test uses its own database, so the shared test database stays untouched.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OpeningBalanceBackfillTest {

    @Autowired
    JdbcTemplate jdbc;

    /** Creating databases needs root, so the test connects as root with the container's root password. */
    static final String ADMIN_USER = "root";

    @Autowired
    MySQLContainer mysql;

    private String rootPassword() {
        String fromContainer = mysql.getEnvMap().get("MYSQL_ROOT_PASSWORD");
        return fromContainer != null ? fromContainer : mysql.getPassword();
    }

    @Test
    void everyExistingAccountGetsAWalletWithItsBalanceAndTheJournalBalances() throws SQLException {
        String database = createDatabase();
        try {
            String url = urlFor(database);
            migrateTo(url, "1");
            JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(url, ADMIN_USER, rootPassword()));
            String zero = insertAccount(db, "0.00");
            String small = insertAccount(db, "250.50");
            String large = insertAccount(db, "9000.00");

            migrateToLatest(url);

            // every account is linked to a wallet with the same balance
            List<Map<String, Object>> links = db.queryForList("""
                    SELECT a.id, a.balance AS account_balance, la.balance AS wallet_balance,
                           la.owner_type, la.type, la.currency
                    FROM accounts a JOIN ledger_account la ON la.id = a.ledger_account_id
                    """);
            assertEquals(3, links.size());
            for (Map<String, Object> link : links) {
                assertEquals(0, ((BigDecimal) link.get("account_balance"))
                        .compareTo((BigDecimal) link.get("wallet_balance")), "wallet mirrors accounts.balance");
                assertEquals("USER", link.get("owner_type"));
                assertEquals("LIABILITY", link.get("type"));
                assertEquals("NGN", link.get("currency"));
            }

            // only the positive balances get an opening entry, keyed by account
            assertEquals(2, count(db, "SELECT COUNT(*) FROM journal_entry WHERE type = 'OPENING_BALANCE'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = 'backfill-" + small + "'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = 'backfill-" + large + "'"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = 'backfill-" + zero + "'"));

            // the equity account holds the negative of everything credited
            BigDecimal equity = db.queryForObject(
                    "SELECT balance FROM ledger_account WHERE code = 'OPENING_BALANCE_EQUITY:NGN'", BigDecimal.class);
            assertEquals(0, equity.compareTo(new BigDecimal("-9250.50")));

            LedgerAssertions.assertReconciles(db);
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void aNegativeAccountStopsTheMigrationBeforeAnythingIsWritten() throws SQLException {
        String database = createDatabase();
        try {
            String url = urlFor(database);
            migrateTo(url, "1");
            JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(url, ADMIN_USER, rootPassword()));
            insertAccount(db, "100.00");
            insertAccount(db, "-5.00");

            assertThrows(FlywayException.class, () -> migrateToLatest(url));

            assertEquals(0, count(db, "SELECT COUNT(*) FROM journal_entry"), "nothing is written");
            assertEquals(0, count(db, "SELECT COUNT(*) FROM ledger_account WHERE owner_type = 'USER'"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM accounts WHERE ledger_account_id IS NOT NULL"));
        } finally {
            dropDatabase(database);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String createDatabase() throws SQLException {
        String name = "backfill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        admin().execute("CREATE DATABASE " + name);
        return name;
    }

    private void dropDatabase(String name) throws SQLException {
        admin().execute("DROP DATABASE IF EXISTS " + name);
    }

    /** A root connection to the test server, for creating and dropping the scratch databases. */
    private JdbcTemplate admin() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            return new JdbcTemplate(new DriverManagerDataSource(connection.getMetaData().getURL(), ADMIN_USER, rootPassword()));
        }
    }

    /** The test server's JDBC URL, pointed at another database. */
    private String urlFor(String database) throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            return connection.getMetaData().getURL().replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
        }
    }

    private void migrateTo(String url, String version) {
        Flyway.configure().dataSource(url, ADMIN_USER, rootPassword()).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private void migrateToLatest(String url) {
        Flyway.configure().dataSource(url, ADMIN_USER, rootPassword()).locations("classpath:db/migration").load().migrate();
    }

    /** A row in the V1 shape: the columns that existed before the ledger. */
    /** V7 on a database where accounts were opened after V4 and have no wallet, then a second run of V7. */
    @Test
    void accountsWithoutAWalletGetOneFromV7AndASecondRunChangesNothing() throws SQLException {
        String database = createDatabase();
        try {
            String url = urlFor(database);
            migrateTo(url, "4");
            JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(url, ADMIN_USER, rootPassword()));
            String funded = insertAccount(db, "250.50");   // opened after V4: no wallet
            String empty = insertAccount(db, "0.00");      // opened after V4: no wallet, no money
            migrateToLatest(url);

            assertEquals(0L, count(db, "SELECT COUNT(*) FROM accounts WHERE ledger_account_id IS NULL"),
                    "every account has a wallet after V7");
            BigDecimal walletOfFunded = db.queryForObject(
                    "SELECT la.balance FROM accounts a JOIN ledger_account la ON la.id = a.ledger_account_id WHERE a.id = ?",
                    BigDecimal.class, funded);
            assertEquals(0, new BigDecimal("250.50").compareTo(walletOfFunded), "the wallet holds the balance");
            assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?",
                    Long.class, "backfill-" + funded), "one opening entry for the funded account");
            assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?",
                    Long.class, "backfill-" + empty), "no opening entry for an empty account");
            BigDecimal equity = db.queryForObject(
                    "SELECT balance FROM ledger_account WHERE code = 'OPENING_BALANCE_EQUITY:NGN'", BigDecimal.class);
            assertEquals(0, new BigDecimal("-250.50").compareTo(equity), "equity holds the negative of what was credited");

            long entriesBefore = count(db, "SELECT COUNT(*) FROM journal_entry");
            long linesBefore = count(db, "SELECT COUNT(*) FROM journal_line");
            executeScript(url, "db/migration/V7__repair_orphan_wallets.sql");
            assertEquals(entriesBefore, count(db, "SELECT COUNT(*) FROM journal_entry"), "a second run writes no entry");
            assertEquals(linesBefore, count(db, "SELECT COUNT(*) FROM journal_line"), "a second run writes no line");
            assertEquals(0, new BigDecimal("-250.50").compareTo(db.queryForObject(
                    "SELECT balance FROM ledger_account WHERE code = 'OPENING_BALANCE_EQUITY:NGN'", BigDecimal.class)),
                    "a second run does not move the equity account");
        } finally {
            dropDatabase(database);
        }
    }

    /** Runs a migration file again on one database, as a second application would. */
    private void executeScript(String url, String resource) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, ADMIN_USER, rootPassword())) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(resource)));
        }
    }

    private String insertAccount(JdbcTemplate db, String balance) {
        String id = UUID.randomUUID().toString();
        String accountNumber = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        String phone = "080" + String.format("%08d", ThreadLocalRandom.current().nextLong(100_000_000L));
        db.update("""
                INSERT INTO accounts (id, account_holder_name, account_number, account_status, account_type, balance,
                                      created_at, daily_transaction_limit, email, phone, updated_at)
                VALUES (?, 'Backfill Test', ?, 'ACTIVE', 'SAVINGS', ?, NOW(6), 100000.00, ?, ?, NOW(6))
                """, id, accountNumber, new BigDecimal(balance), id + "@example.test", phone);
        return id;
    }

    private static long count(JdbcTemplate db, String sql) {
        return db.queryForObject(sql, Long.class);
    }
}
