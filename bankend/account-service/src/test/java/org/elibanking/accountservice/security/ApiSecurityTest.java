package org.elibanking.accountservice.security;

import org.elibanking.accountservice.TestTokens;
import org.elibanking.accountservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The legacy path is off (the default). Every endpoint needs a token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApiSecurityTest {

    private static final String ACCOUNT = "1234567890";

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeEach
    void client() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void theOldEndpointsNeedATokenWhenTheLegacyFlagIsOff() {
        assertEquals(401, get("/api/v1/accounts/" + ACCOUNT + "/balance", null));
        assertEquals(401, get("/api/v1/accounts/" + ACCOUNT, null));
        assertEquals(401, put("/api/v1/accounts/" + ACCOUNT + "/deduct?amount=1", null));
        assertEquals(401, put("/api/v1/accounts/" + ACCOUNT + "/credit?amount=1", null));
    }

    @Test
    void theLedgerApiAcceptsServiceTokensOnly() {
        String ledger = "/internal/v1/ledger/accounts/1";
        assertEquals(401, get(ledger, null), "no token");
        assertEquals(403, get(ledger, TestTokens.userToken("user-1", "ADMIN")),
                "a user token, even an ADMIN one, is not a service token");
        assertEquals(404, get(ledger, TestTokens.serviceToken()),
                "a service token gets through; the endpoint itself is not built yet");
    }

    @Test
    void aServiceTokenForAnotherAudienceIsRejected() {
        assertEquals(401, get("/internal/v1/ledger/accounts/1", TestTokens.serviceTokenFor("wallet-service")));
    }

    @Test
    void aTokenSignedWithTheWrongSecretIsRejected() {
        assertEquals(401, get("/api/v1/accounts/me", TestTokens.userTokenWithTheWrongSecret("user-1")));
    }

    @Test
    void anExpiredTokenIsRejected() {
        assertEquals(401, get("/api/v1/accounts/me", TestTokens.expiredUserToken("user-1")));
    }

    @Test
    void aUserReadsTheirOwnAccounts() {
        assertEquals(200, get("/api/v1/accounts/me", TestTokens.userToken("user-1", "USER")));
    }

    @Test
    void aUserCannotListAllAccountsOrUseTheAdminDeduct() {
        assertEquals(403, get("/api/v1/accounts", TestTokens.userToken("user-1", "USER")));
        assertEquals(403, put("/api/v1/accounts/" + ACCOUNT + "/deduct?amount=1",
                TestTokens.userToken("user-1", "USER")));
        assertEquals(200, get("/api/v1/accounts", TestTokens.userToken("admin-1", "ADMIN")));
    }

    @Test
    void healthIsPublic() {
        assertEquals(200, get("/actuator/health", null));
    }

    private int get(String path, String bearer) {
        return client.get().uri(path).headers(h -> {
            if (bearer != null) {
                h.setBearerAuth(bearer);
            }
        }).exchange((request, response) -> response.getStatusCode().value());
    }

    private int put(String path, String bearer) {
        return client.put().uri(path).headers(h -> {
            if (bearer != null) {
                h.setBearerAuth(bearer);
            }
        }).exchange((request, response) -> response.getStatusCode().value());
    }
}
