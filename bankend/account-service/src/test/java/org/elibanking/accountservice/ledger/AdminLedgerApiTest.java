package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestTokens;
import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reconciliation, the statement and the admin adjustments, over HTTP with real tokens. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AdminLedgerApiTest {

    private static final Pattern LINE_ID = Pattern.compile("\"lineId\":(\\d+)");
    private static final Pattern NEXT = Pattern.compile("\"nextAfterLineId\":(\\d+)");
    private static final Pattern BALANCE = Pattern.compile("\"balance\":\"([^\"]+)\"");
    private static final Pattern REPLAYED = Pattern.compile("\"replayed\":\\s*(true|false)");
    private static final Pattern ENTRY_ID = Pattern.compile("\"entryId\":\\s*(\\d+)");

    private static long entryIdOf(String body) {
        Matcher m = ENTRY_ID.matcher(body);
        assertTrue(m.find(), "no entryId in " + body);
        return Long.parseLong(m.group(1));
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerPostingService ledger;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
    }

    record Response(int status, String body) {
    }

    @Test
    void reconciliationIsForAdminsOnly() {
        assertEquals(401, send(HttpMethod.GET, "/api/v1/admin/ledger/reconciliation", null, null).status());
        assertEquals(403, send(HttpMethod.GET, "/api/v1/admin/ledger/reconciliation",
                TestTokens.userToken("user-1", "USER"), null).status());
        Response admin = send(HttpMethod.GET, "/api/v1/admin/ledger/reconciliation",
                TestTokens.userToken("admin-1", "ADMIN"), null);
        assertEquals(200, admin.status());
        assertTrue(admin.body().contains("\"clean\":true"), "the ledger is consistent: " + admin.body());
    }

    @Test
    void theStatementShowsRunningBalancesAndPagesByLine() {
        String owner = "owner-" + UUID.randomUUID();
        String account = wallet(owner, "0.00");
        post(account, "CREDIT", "10.00");
        post(account, "CREDIT", "20.00");
        post(account, "DEBIT", "5.00");
        String ownerToken = TestTokens.userToken(owner, "USER");

        Response first = send(HttpMethod.GET, "/api/v1/accounts/" + account + "/statement?size=2", ownerToken, null);
        assertEquals(200, first.status());
        assertTrue(first.body().contains("\"openingBalance\":\"0.00\""), first.body());
        assertTrue(first.body().contains("\"runningBalance\":\"10.00\""), first.body());
        assertTrue(first.body().contains("\"runningBalance\":\"30.00\""), first.body());
        Matcher next = NEXT.matcher(first.body());
        assertTrue(next.find(), "a full page has a cursor: " + first.body());
        long cursor = Long.parseLong(next.group(1));

        Response second = send(HttpMethod.GET,
                "/api/v1/accounts/" + account + "/statement?size=2&afterLineId=" + cursor, ownerToken, null);
        assertEquals(200, second.status());
        assertTrue(second.body().contains("\"openingBalance\":\"30.00\""), "the opening balance is the balance before the page");
        assertTrue(second.body().contains("\"runningBalance\":\"25.00\""), second.body());
        assertTrue(second.body().contains("\"nextAfterLineId\":null"), "the last page has no cursor: " + second.body());
    }

    @Test
    void theStatementIsForTheOwnerOrAnAdmin() {
        String owner = "owner-" + UUID.randomUUID();
        String account = wallet(owner, "0.00");
        post(account, "CREDIT", "10.00");

        assertEquals(403, send(HttpMethod.GET, "/api/v1/accounts/" + account + "/statement",
                TestTokens.userToken("someone-else", "USER"), null).status());
        assertEquals(200, send(HttpMethod.GET, "/api/v1/accounts/" + account + "/statement",
                TestTokens.userToken(owner, "USER"), null).status());
        assertEquals(200, send(HttpMethod.GET, "/api/v1/accounts/" + account + "/statement",
                TestTokens.userToken("admin-1", "ADMIN"), null).status());
        assertEquals(401, send(HttpMethod.GET, "/api/v1/accounts/" + account + "/statement", null, null).status());
    }

    @Test
    void anAdminCreditIsAppliedOnceAndAuditedOnce() {
        String account = wallet("owner-" + UUID.randomUUID(), "0.00");
        String key = "adj-" + UUID.randomUUID();
        String admin = TestTokens.userToken("admin-1", "ADMIN");

        Response first = send(HttpMethod.PUT, "/api/v1/admin/accounts/" + account + "/credit?amount=50.00&reason=bonus",
                admin, key);
        assertEquals(201, first.status());
        assertTrue(first.body().contains("\"balance\":\"50.00\""), first.body());

        Response replay = send(HttpMethod.PUT, "/api/v1/admin/accounts/" + account + "/credit?amount=50.00&reason=bonus",
                admin, key);
        // A replay returns the stored response, with the status it had (wallet-plan 3.6). MySQL stores the body as JSON, so
        // whitespace differs from the first answer; compare the content, not the bytes.
        assertEquals(201, replay.status(), "a replay returns the stored response");
        assertTrue(REPLAYED.matcher(replay.body()).find(), replay.body());
        assertEquals(entryIdOf(first.body()), entryIdOf(replay.body()), "the same entry comes back");
        assertEquals("50.00", balance(account), "the credit is applied once");

        assertEquals(422, send(HttpMethod.PUT, "/api/v1/admin/accounts/" + account + "/credit?amount=51.00&reason=bonus",
                admin, key).status(), "the same key with a different amount is a conflict");

        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'ADMIN_CREDIT' AND correlation_id = ?",
                Long.class, account, key), "one audit row, from the first attempt only");
    }

    @Test
    void anAdminDeductCannotTakeTheWalletBelowZero() {
        String account = wallet("owner-" + UUID.randomUUID(), "50.00");
        String admin = TestTokens.userToken("admin-1", "ADMIN");

        assertEquals(422, send(HttpMethod.PUT, "/api/v1/admin/accounts/" + account + "/deduct?amount=60.00&reason=fee",
                admin, "adj-" + UUID.randomUUID()).status());
        assertEquals(201, send(HttpMethod.PUT, "/api/v1/admin/accounts/" + account + "/deduct?amount=20.00&reason=fee",
                admin, "adj-" + UUID.randomUUID()).status());
        assertEquals("30.00", balance(account));
    }

    @Test
    void anAdjustmentNeedsAReasonAKeyAndAnAdmin() {
        String account = wallet("owner-" + UUID.randomUUID(), "0.00");
        String admin = TestTokens.userToken("admin-1", "ADMIN");

        assertEquals(400, send(HttpMethod.PUT, "/api/v1/admin/accounts/" + account + "/credit?amount=1.00&reason=",
                admin, "adj-" + UUID.randomUUID()).status(), "a reason is required");
        assertEquals(400, send(HttpMethod.PUT, "/api/v1/admin/accounts/" + account + "/credit?amount=1.00&reason=x",
                admin, null).status(), "an Idempotency-Key is required");
        assertEquals(403, send(HttpMethod.PUT, "/api/v1/admin/accounts/" + account + "/credit?amount=1.00&reason=x",
                TestTokens.userToken("user-1", "USER"), "adj-" + UUID.randomUUID()).status());
        assertEquals("0.00", balance(account));
    }

    // ------------------------------------------------------------------ helpers

    private Response send(HttpMethod method, String uri, String bearer, String idempotencyKey) {
        ResponseEntity<String> entity = client.method(method).uri(uri)
                .headers(h -> {
                    if (bearer != null) {
                        h.setBearerAuth(bearer);
                    }
                    if (idempotencyKey != null) {
                        h.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(String.class);
        return new Response(entity.getStatusCode().value(), entity.getBody());
    }

    private String balance(String accountNumber) {
        Response response = send(HttpMethod.GET, "/internal/v1/ledger/accounts/" + accountNumber,
                TestTokens.serviceToken(), null);
        Matcher m = BALANCE.matcher(response.body());
        assertTrue(m.find(), "no balance: " + response.body());
        return m.group(1);
    }

    private void post(String accountNumber, String side, String amount) {
        long wallet = jdbc.queryForObject("SELECT ledger_account_id FROM accounts WHERE account_number = ?",
                Long.class, accountNumber);
        long equity = jdbc.queryForObject(
                "SELECT id FROM ledger_account WHERE code = 'OPENING_BALANCE_EQUITY:NGN'", Long.class);
        Money money = Money.of(amount, "NGN");
        Side walletSide = Side.valueOf(side);
        ledger.post(PostingRequest.of("TEST", "stmt-" + UUID.randomUUID(), List.of(
                new PostingLine(wallet, walletSide, money),
                new PostingLine(equity, walletSide.opposite(), money))));
    }

    /** A customer wallet with an accounts row owned by {@code ownerId}, funded through the ledger. */
    private String wallet(String ownerId, String balance) {
        String ownerRef = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ledger_account (owner_type, owner_ref, type, currency) VALUES ('USER', ?, 'LIABILITY', 'NGN')",
                ownerRef);
        long wallet = jdbc.queryForObject("SELECT id FROM ledger_account WHERE owner_ref = ?", Long.class, ownerRef);
        String accountNumber = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        String phone = "084" + String.format("%08d", ThreadLocalRandom.current().nextLong(100_000_000L));
        jdbc.update("""
                INSERT INTO accounts (id, account_holder_name, account_number, account_status, account_type, balance,
                                      created_at, daily_transaction_limit, email, phone, updated_at, currency,
                                      ledger_account_id, owner_id)
                VALUES (?, 'Statement Test', ?, 'ACTIVE', 'SAVINGS', 0.00, NOW(6), 100000.00, ?, ?, NOW(6), 'NGN', ?, ?)
                """, UUID.randomUUID().toString(), accountNumber, ownerRef + "@example.test", phone, wallet, ownerId);
        if (new java.math.BigDecimal(balance).signum() > 0) {
            long equity = jdbc.queryForObject(
                    "SELECT id FROM ledger_account WHERE code = 'OPENING_BALANCE_EQUITY:NGN'", Long.class);
            ledger.post(PostingRequest.of("FUNDING", "fund-" + UUID.randomUUID(), List.of(
                    new PostingLine(equity, Side.DEBIT, Money.of(balance, "NGN")),
                    new PostingLine(wallet, Side.CREDIT, Money.of(balance, "NGN")))));
        }
        return accountNumber;
    }
}
