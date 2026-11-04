package org.elibanking.accountservice.security;

import org.elibanking.accountservice.TestTokens;
import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.common.security.ServiceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The contract between the callers and account-service: a token minted by the shared {@link ServiceTokens} (the code the
 * callers run) is accepted, and a token for another audience or signed with another secret is refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ServiceTokenContractTest {

    /** Any account number that does not exist: an accepted token gets 404, a refused one gets 401. */
    private static final String PATH = "/internal/v1/ledger/accounts/0000000000";

    @LocalServerPort
    int port;

    @Test
    void aTokenMintedByTheCallersIsAccepted() {
        String token = new ServiceTokens(TestTokens.SERVICE_SECRET, "transaction-service", TestTokens.AUDIENCE).token();

        assertEquals(404, status(token), "authenticated: the account simply does not exist");
    }

    @Test
    void aTokenForAnotherServiceIsRefused() {
        String token = new ServiceTokens(TestTokens.SERVICE_SECRET, "transaction-service", "some-other-service").token();

        assertEquals(401, status(token), "the audience must name account-service");
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRefused() {
        String token = new ServiceTokens("another-service-secret-0123456789abcdef", "transaction-service",
                TestTokens.AUDIENCE).token();

        assertEquals(401, status(token), "the signature must match INTERNAL_JWT_SECRET");
    }

    private int status(String token) {
        return RestClient.create("http://localhost:" + port).get().uri(PATH)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toBodilessEntity()
                .getStatusCode().value();
    }
}
