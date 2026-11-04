package org.elibanking.accountservice.security;

import org.elibanking.accountservice.TestTokens;
import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.accountservice.ledger.LedgerApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each endpoint accepts only the services named for it (wallet-plan 3.7, see {@link CallerAllowList}). A refused call gets
 * 403, and it writes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CallerAllowListTest {

    /** An account number that does not exist: an authorized call gets 404, a refused one gets 403. */
    private static final String UNKNOWN = "0000000000";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private RestClient client;

    @BeforeEach
    void client() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void paymentAndFraudCannotPostLedgerEntries() {
        for (String service : List.of(CallerAllowList.PAYMENT, CallerAllowList.FRAUD)) {
            String key = "allow-" + UUID.randomUUID();

            assertEquals(403, post("/internal/v1/ledger/entries", entry(key), TestTokens.serviceTokenAs(service)), service);
            assertEquals(0L, entriesWithKey(key), service + " wrote nothing");
        }
    }

    @Test
    void transactionAndWalletMayPostLedgerEntries() {
        for (String service : List.of(CallerAllowList.TRANSACTION, CallerAllowList.WALLET)) {
            assertEquals(201, post("/internal/v1/ledger/entries", entry("allow-" + UUID.randomUUID()),
                    TestTokens.serviceTokenAs(service)), service);
        }
    }

    @Test
    void aServiceOutsideTheListsIsRefusedEverywhereInTheLedgerApi() {
        String token = TestTokens.serviceTokenAs("notification-service");

        assertEquals(403, post("/internal/v1/ledger/entries", entry("allow-" + UUID.randomUUID()), token));
        assertEquals(403, get("/internal/v1/ledger/accounts/" + UNKNOWN, token));
    }

    @Test
    void paymentFraudAndWalletMayReadTheLedgerAccounts() {
        for (String service : List.of(CallerAllowList.PAYMENT, CallerAllowList.FRAUD, CallerAllowList.WALLET)) {
            assertEquals(404, get("/internal/v1/ledger/accounts/" + UNKNOWN, TestTokens.serviceTokenAs(service)),
                    service + " is authorized to read; the account does not exist");
        }
    }

    @Test
    void onlyTransactionMayUseTheOldDeductAndCredit() {
        assertEquals(404, put("/api/v1/accounts/" + UNKNOWN + "/deduct?amount=1.00",
                TestTokens.serviceTokenAs(CallerAllowList.TRANSACTION)), "authorized; the account does not exist");
        assertEquals(404, put("/api/v1/accounts/" + UNKNOWN + "/credit?amount=1.00",
                TestTokens.serviceTokenAs(CallerAllowList.TRANSACTION)), "authorized; the account does not exist");

        for (String service : List.of(CallerAllowList.PAYMENT, CallerAllowList.FRAUD, CallerAllowList.WALLET)) {
            assertEquals(403, put("/api/v1/accounts/" + UNKNOWN + "/deduct?amount=1.00",
                    TestTokens.serviceTokenAs(service)), service);
        }
        assertEquals(403, put("/api/v1/accounts/" + UNKNOWN + "/deduct?amount=1.00",
                TestTokens.userToken("admin-1", "ADMIN")), "an admin uses /api/v1/admin/accounts/{n}/deduct instead");
    }

    @Test
    void paymentAndFraudMayReadAnAccount() {
        for (String service : List.of(CallerAllowList.PAYMENT, CallerAllowList.FRAUD)) {
            assertEquals(404, get("/api/v1/accounts/" + UNKNOWN, TestTokens.serviceTokenAs(service)), service);
            assertEquals(404, get("/api/v1/accounts/" + UNKNOWN + "/balance", TestTokens.serviceTokenAs(service)), service);
        }
        assertEquals(403, get("/api/v1/accounts/" + UNKNOWN, TestTokens.serviceTokenAs("notification-service")));
    }

    // ------------------------------------------------------------------ helpers

    /** Two system accounts, so the entry needs no funded wallet. The key is unique to the call. */
    private static LedgerApi.PostEntryRequest entry(String key) {
        return new LedgerApi.PostEntryRequest("TEST", key, null, null, "test", List.of(
                new LedgerApi.LineRequest(null, "PAYOUT_PAYABLE:NGN", "DEBIT", "1.00", "NGN"),
                new LedgerApi.LineRequest(null, "PROVIDER_CLEARING:STRIPE:NGN", "CREDIT", "1.00", "NGN")));
    }

    private long entriesWithKey(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?", Long.class, key);
    }

    private int post(String path, Object body, String bearer) {
        return client.post().uri(path)
                .headers(h -> h.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private int put(String path, String bearer) {
        return client.put().uri(path)
                .headers(h -> h.setBearerAuth(bearer))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private int get(String path, String bearer) {
        return client.get().uri(path)
                .headers(h -> h.setBearerAuth(bearer))
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
