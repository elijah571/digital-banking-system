package org.elibanking.accountservice.security;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy path is on. The four old endpoints get through without a token, and each such request is logged. Everything
 * else still needs a token, and the ledger API still needs a service token (wallet-plan 8.18, 8.19).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "account.legacy-headers.enabled=true")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LegacyHeadersEnabledTest {

    private static final String ACCOUNT = "1234567890";

    @LocalServerPort
    int port;

    @Test
    void theOldCallersGetThroughWithoutATokenAndTheRequestIsLogged(CapturedOutput output) {
        // The account does not exist, so the answer is 404: the request got past the security check, which is the point.
        assertEquals(404, put("/api/v1/accounts/" + ACCOUNT + "/deduct?amount=1"));
        assertTrue(output.getOut().contains("legacy header access: PUT /api/v1/accounts/" + ACCOUNT + "/deduct"),
                "the legacy access must be logged with the caller's details");
    }

    @Test
    void theBalanceAndTheGetByNumberGetThroughWithoutAToken() {
        assertEquals(404, get("/api/v1/accounts/" + ACCOUNT + "/balance"));
        assertEquals(404, get("/api/v1/accounts/" + ACCOUNT));
    }

    @Test
    void theLedgerApiStillNeedsAServiceTokenWithTheFlagOn() {
        assertEquals(401, get("/internal/v1/ledger/accounts/1"));
    }

    @Test
    void otherEndpointsStillNeedATokenWithTheFlagOn() {
        assertEquals(401, get("/api/v1/accounts/me"));
        assertEquals(401, get("/api/v1/accounts"));
        assertEquals(401, put("/api/v1/accounts/" + ACCOUNT + "/block"));
    }

    private int get(String path) {
        return client().get().uri(path).exchange((request, response) -> response.getStatusCode().value());
    }

    private int put(String path) {
        return client().put().uri(path).exchange((request, response) -> response.getStatusCode().value());
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
