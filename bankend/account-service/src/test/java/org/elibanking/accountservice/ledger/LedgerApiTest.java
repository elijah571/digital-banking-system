package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestTokens;
import org.elibanking.accountservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal ledger API, over HTTP, with a real service token. Each answer is checked against the database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LedgerApiTest {

    private static final Pattern ENTRY_ID = Pattern.compile("\"entryId\":(\\d+)");
    private static final Pattern REPLAYED = Pattern.compile("\"replayed\":(true|false)");
    private static final Pattern BALANCE = Pattern.compile("\"balance\":\"([^\"]+)\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerPostingService ledger;

    private RestClient client;
    private String service;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        service = TestTokens.serviceToken();
    }

    @Test
    void aPostingMovesMoneyOnceAndAReplayReturnsTheSameEntry() {
        String a = wallet("100.00");
        String b = wallet("0.00");
        String key = key();
        LedgerApi.PostEntryRequest request = entry(key, account(a, "DEBIT", "30.00"), account(b, "CREDIT", "30.00"));

        Response first = post(request, service);
        assertEquals(201, first.status());
        long entryId = entryId(first.body());
        assertEquals("false", replayed(first.body()));

        Response again = post(request, service);
        assertEquals(200, again.status(), "a replay is 200, not 201");
        assertEquals(entryId, entryId(again.body()));
        assertEquals("true", replayed(again.body()));

        assertEquals("70.00", balance(a));
        assertEquals("30.00", balance(b));
    }

    @Test
    void anUnbalancedEntryIsRejectedAndWritesNothing() {
        String a = wallet("100.00");
        String b = wallet("0.00");

        Response response = post(entry(key(), account(a, "DEBIT", "10.00"), account(b, "CREDIT", "9.00")), service);

        assertEquals(400, response.status());
        assertEquals("100.00", balance(a));
    }

    @Test
    void insufficientFundsIsRejectedAndWritesNothing() {
        String a = wallet("20.00");
        String b = wallet("0.00");

        Response response = post(entry(key(), account(a, "DEBIT", "30.00"), account(b, "CREDIT", "30.00")), service);

        assertEquals(422, response.status());
        assertEquals("20.00", balance(a));
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() {
        String a = wallet("100.00");
        String b = wallet("0.00");
        String key = key();
        assertEquals(201, post(entry(key, account(a, "DEBIT", "30.00"), account(b, "CREDIT", "30.00")), service).status());

        Response response = post(entry(key, account(a, "DEBIT", "31.00"), account(b, "CREDIT", "31.00")), service);

        assertEquals(422, response.status());
        assertEquals("70.00", balance(a));
    }

    @Test
    void anEntryCanBeReadByKeyAndReversedOnlyOnce() {
        String a = wallet("100.00");
        String b = wallet("0.00");
        String key = key();
        long entryId = entryId(post(entry(key, account(a, "DEBIT", "30.00"), account(b, "CREDIT", "30.00")), service).body());

        Response read = get("/internal/v1/ledger/entries/by-key/" + key, service);
        assertEquals(200, read.status());
        assertTrue(read.body().contains("\"amount\":\"30.00\""), "amounts travel as strings: " + read.body());

        Response reversal = post("/internal/v1/ledger/entries/" + entryId + "/reverse",
                new LedgerApi.ReverseRequest(key(), "test"), service);
        assertEquals(201, reversal.status());
        assertEquals("100.00", balance(a));
        assertEquals("0.00", balance(b));

        Response second = post("/internal/v1/ledger/entries/" + entryId + "/reverse",
                new LedgerApi.ReverseRequest(key(), "test"), service);
        assertEquals(409, second.status(), "an entry is reversed once");
    }

    @Test
    void aSystemAccountCanBeNamedByCode() {
        String a = wallet("100.00");

        Response response = post(entry(key(), account(a, "DEBIT", "5.00"),
                new LedgerApi.LineRequest(null, "PAYOUT_PAYABLE:NGN", "CREDIT", "5.00", "NGN")), service);

        assertEquals(201, response.status());
        assertEquals("95.00", balance(a));
    }

    @Test
    void anUnknownAccountIsNotFound() {
        Response response = post(entry(key(), account("0000000000", "DEBIT", "1.00"),
                new LedgerApi.LineRequest(null, "PAYOUT_PAYABLE:NGN", "CREDIT", "1.00", "NGN")), service);

        assertEquals(404, response.status());
    }

    @Test
    void aLineNamesExactlyOneAccount() {
        String a = wallet("100.00");
        LedgerApi.LineRequest both = new LedgerApi.LineRequest(a, "PAYOUT_PAYABLE:NGN", "DEBIT", "1.00", "NGN");
        LedgerApi.LineRequest neither = new LedgerApi.LineRequest(null, null, "CREDIT", "1.00", "NGN");

        assertEquals(400, post(entry(key(), both, neither), service).status());
    }

    @Test
    void theAccountViewReturnsTheBalanceAsAString() {
        String a = wallet("100.00");

        Response response = get("/internal/v1/ledger/accounts/" + a, service);

        assertEquals(200, response.status());
        assertEquals("100.00", balance(a));
    }

    @Test
    void aUserTokenCannotUseTheLedgerApi() {
        String a = wallet("100.00");

        assertEquals(403, post(entry(key(), account(a, "DEBIT", "1.00"),
                new LedgerApi.LineRequest(null, "PAYOUT_PAYABLE:NGN", "CREDIT", "1.00", "NGN")),
                TestTokens.userToken("user-1", "ADMIN")).status());
    }

    @Test
    void aPostingWritesOneAuditRowWithTheTokenSubjectAndTheRequestId() {
        String a = wallet("100.00");
        String b = wallet("0.00");
        String requestId = "req-" + UUID.randomUUID();
        LedgerApi.PostEntryRequest request = entry(key(), account(a, "DEBIT", "30.00"), account(b, "CREDIT", "30.00"));

        Response first = post("/internal/v1/ledger/entries", request, service, requestId);
        assertEquals(201, first.status());
        long entryId = entryId(first.body());
        assertEquals(requestId, first.requestId(), "the response echoes X-Request-Id");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor, action, correlation_id FROM audit_log WHERE entity_type = 'JOURNAL_ENTRY' AND entity_id = ?",
                String.valueOf(entryId));
        assertEquals("transaction-service", row.get("actor"), "the actor is the service token's subject");
        assertEquals("LEDGER_ENTRY_POSTED", row.get("action"));
        assertEquals(requestId, row.get("correlation_id"));

        Response replay = post("/internal/v1/ledger/entries", request, service, requestId);
        assertEquals(200, replay.status());
        assertEquals(1L, auditRowsFor(entryId), "a replay writes no second audit row");
    }

    @Test
    void aReversalIsAuditedAgainstTheEntryItReverses() {
        String a = wallet("100.00");
        String b = wallet("0.00");
        long entryId = entryId(post(entry(key(), account(a, "DEBIT", "30.00"), account(b, "CREDIT", "30.00")), service).body());
        String requestId = "req-" + UUID.randomUUID();

        Response reversal = post("/internal/v1/ledger/entries/" + entryId + "/reverse",
                new LedgerApi.ReverseRequest(key(), "test"), service, requestId);
        assertEquals(201, reversal.status());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT action, actor, correlation_id,
                       JSON_UNQUOTE(JSON_EXTRACT(details, '$.reverses')) AS reverses
                FROM audit_log WHERE entity_type = 'JOURNAL_ENTRY' AND entity_id = ?
                """, String.valueOf(entryId(reversal.body())));
        assertEquals("LEDGER_ENTRY_REVERSED", row.get("action"));
        assertEquals("transaction-service", row.get("actor"));
        assertEquals(requestId, row.get("correlation_id"));
        assertEquals(String.valueOf(entryId), row.get("reverses"));
    }

    @Test
    void aMissingRequestIdIsGeneratedAndAnInvalidOneIsRejectedWithNothingMoved() {
        String a = wallet("100.00");
        String b = wallet("0.00");

        Response generated = post("/internal/v1/ledger/entries",
                entry(key(), account(a, "DEBIT", "1.00"), account(b, "CREDIT", "1.00")), service, null);
        assertEquals(201, generated.status());
        assertNotNull(generated.requestId(), "a generated X-Request-Id is returned");
        assertEquals(generated.requestId(), jdbc.queryForObject(
                "SELECT correlation_id FROM audit_log WHERE entity_type = 'JOURNAL_ENTRY' AND entity_id = ?",
                String.class, String.valueOf(entryId(generated.body()))), "the audit row carries the generated id");

        Response rejected = post("/internal/v1/ledger/entries",
                entry(key(), account(a, "DEBIT", "1.00"), account(b, "CREDIT", "1.00")), service, "x".repeat(101));
        assertEquals(400, rejected.status(), "more than 100 characters is not a valid X-Request-Id");
        assertEquals("99.00", balance(a), "the rejected request moved nothing");
    }

    @Test
    void aServiceTokenWithoutASubjectCannotPost() {
        String a = wallet("100.00");
        String b = wallet("0.00");

        assertEquals(401, post(entry(key(), account(a, "DEBIT", "1.00"), account(b, "CREDIT", "1.00")),
                TestTokens.serviceTokenWithoutSubject()).status(), "the subject is the audit actor, so it is required");
        assertEquals("100.00", balance(a));
    }

    // ------------------------------------------------------------------ helpers

    record Response(int status, String body, String requestId) {
    }

    private long auditRowsFor(long entryId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'JOURNAL_ENTRY' AND entity_id = ?",
                Long.class, String.valueOf(entryId));
    }

    private Response post(LedgerApi.PostEntryRequest request, String bearer) {
        return post("/internal/v1/ledger/entries", request, bearer);
    }

    private Response post(String path, Object body, String bearer) {
        return post(path, body, bearer, null);
    }

    private Response post(String path, Object body, String bearer, String requestId) {
        ResponseEntity<String> entity = client.post().uri(path)
                .headers(h -> {
                    h.setBearerAuth(bearer);
                    if (requestId != null) {
                        h.set(LedgerController.REQUEST_ID, requestId);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(String.class);
        return response(entity);
    }

    private Response get(String path, String bearer) {
        ResponseEntity<String> entity = client.get().uri(path)
                .headers(h -> h.setBearerAuth(bearer))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(String.class);
        return response(entity);
    }

    private static Response response(ResponseEntity<String> entity) {
        return new Response(entity.getStatusCode().value(), entity.getBody(),
                entity.getHeaders().getFirst(LedgerController.REQUEST_ID));
    }

    private String balance(String accountNumber) {
        Matcher m = BALANCE.matcher(get("/internal/v1/ledger/accounts/" + accountNumber, service).body());
        assertTrue(m.find(), "no balance in the account view");
        return m.group(1);
    }

    private static long entryId(String body) {
        Matcher m = ENTRY_ID.matcher(body);
        assertTrue(m.find(), "no entryId in " + body);
        return Long.parseLong(m.group(1));
    }

    private static String replayed(String body) {
        Matcher m = REPLAYED.matcher(body);
        assertTrue(m.find(), "no replayed flag in " + body);
        return m.group(1);
    }

    private static String key() {
        return "api-" + UUID.randomUUID();
    }

    private static LedgerApi.PostEntryRequest entry(String key, LedgerApi.LineRequest... lines) {
        return new LedgerApi.PostEntryRequest("TRANSFER", key, null, null, "test", List.of(lines));
    }

    private static LedgerApi.LineRequest account(String accountNumber, String side, String amount) {
        return new LedgerApi.LineRequest(accountNumber, null, side, amount, "NGN");
    }

    /** A customer wallet with an accounts row, funded through the ledger so the mirror is set too. */
    private String wallet(String balance) {
        String ownerRef = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ledger_account (owner_type, owner_ref, type, currency) VALUES ('USER', ?, 'LIABILITY', 'NGN')",
                ownerRef);
        long wallet = jdbc.queryForObject("SELECT id FROM ledger_account WHERE owner_ref = ?", Long.class, ownerRef);
        String accountNumber = String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        String phone = "083" + String.format("%08d", ThreadLocalRandom.current().nextLong(100_000_000L));
        jdbc.update("""
                INSERT INTO accounts (id, account_holder_name, account_number, account_status, account_type, balance,
                                      created_at, daily_transaction_limit, email, phone, updated_at, currency,
                                      ledger_account_id)
                VALUES (?, 'Ledger Api Test', ?, 'ACTIVE', 'SAVINGS', 0.00, NOW(6), 100000.00, ?, ?, NOW(6), 'NGN', ?)
                """, UUID.randomUUID().toString(), accountNumber, ownerRef + "@example.test", phone, wallet);
        if (new BigDecimal(balance).signum() > 0) {
            long equity = jdbc.queryForObject(
                    "SELECT id FROM ledger_account WHERE code = 'OPENING_BALANCE_EQUITY:NGN'", Long.class);
            ledger.post(PostingRequest.of("FUNDING", key(), List.of(
                    new PostingLine(equity, Side.DEBIT, org.elibanking.common.money.Money.of(balance, "NGN")),
                    new PostingLine(wallet, Side.CREDIT, org.elibanking.common.money.Money.of(balance, "NGN")))));
        }
        return accountNumber;
    }
}
