package org.elibanking.transactionservice.service;

import org.elibanking.common.security.ServiceTokens;
import org.elibanking.transactionservice.exception.PinLockedException;
import org.elibanking.transactionservice.exception.PinUnavailableException;
import org.elibanking.transactionservice.exception.TransactionAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpMethod.POST;

/** Each wallet answer to the PIN check becomes the right transfer error (wallet-plan 3.5, D-P8). No network: a mock server. */
class WalletPinVerifierTest {

    private static final String WALLET = "http://wallet.test";
    private static final String PATH = WALLET + "/internal/v1/pin/verify";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ServiceTokens tokens = new ServiceTokens("transaction-service-test-secret-0123456789",
            "transaction-service", "wallet-service");
    private final WalletPinVerifier verifier = new WalletPinVerifier(builder, WALLET, tokens);

    @Test
    void aRightPinPassesAndTheCallCarriesAServiceToken() {
        server.expect(requestTo(PATH)).andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess("{\"valid\":true}", MediaType.APPLICATION_JSON));

        verifier.check("alice", "1234");

        server.verify();
    }

    @Test
    void aWrongPinRefusesTheTransfer() {
        server.expect(requestTo(PATH))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Wrong PIN\"}"));

        TransactionAccessDeniedException refused = assertThrows(TransactionAccessDeniedException.class,
                () -> verifier.check("alice", "9999"));
        assertEquals("Wrong PIN", refused.getMessage());
    }

    @Test
    void aUserWithNoPinIsToldToSetOne() {
        server.expect(requestTo(PATH))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Set a PIN first\"}"));

        TransactionAccessDeniedException refused = assertThrows(TransactionAccessDeniedException.class,
                () -> verifier.check("alice", "1234"));
        assertEquals("Set a PIN first", refused.getMessage());
    }

    @Test
    void aLockedPinGivesTheWaitFromRetryAfter() {
        server.expect(requestTo(PATH))
                .andRespond(withStatus(HttpStatus.LOCKED).header(HttpHeaders.RETRY_AFTER, "1200"));

        PinLockedException locked = assertThrows(PinLockedException.class, () -> verifier.check("alice", "1234"));
        assertEquals(1200, locked.retryAfterSeconds());
    }

    @Test
    void aWalletServerErrorFailsClosed() {
        server.expect(requestTo(PATH)).andRespond(withServerError());

        assertThrows(PinUnavailableException.class, () -> verifier.check("alice", "1234"));
    }

    @Test
    void aWalletThatRejectsOurTokenFailsClosed() {
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(PinUnavailableException.class, () -> verifier.check("alice", "1234"));
    }
}
