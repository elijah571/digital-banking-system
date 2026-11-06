package org.elibanking.walletservice.pin;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internal PIN check (wallet-plan 3.5, D2, D-P8): who may call it, what each answer is, and that the PIN never reaches
 * the logs (threat model T4, T8).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalPinControllerTest {

    private static final String PATH = "/internal/v1/pin/verify";
    private static final String PIN = "2468";

    @Autowired
    MockMvc mvc;

    @Autowired
    PinCredentialRepository credentials;

    @Autowired
    PinHasher hasher;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void stopCapturingLogs() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        logs.stop();
    }

    @Test
    void theRightPinIsOk() throws Exception {
        String user = aUserWithPin(PIN);

        mvc.perform(verify(transaction(), user, PIN)).andExpect(status().isOk())
                .andExpect(content().json("{\"valid\":true}"));
    }

    @Test
    void aWrongPinIsForbiddenAndSaysSo() throws Exception {
        String user = aUserWithPin(PIN);

        mvc.perform(verify(transaction(), user, "1111")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Wrong PIN"));
    }

    @Test
    void aUserWithNoPinIsToldToSetOne() throws Exception {
        mvc.perform(verify(transaction(), "user-" + UUID.randomUUID(), PIN)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Set a PIN first"));
    }

    @Test
    void aLockedPinIsRefusedWithRetryAfter() throws Exception {
        String user = aUserWithPin(PIN);
        for (int i = 0; i < 5; i++) {
            mvc.perform(verify(transaction(), user, "1111"));
        }

        mvc.perform(verify(transaction(), user, PIN)).andExpect(status().isLocked())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void aMalformedPinIsABadRequestAndIsNotCounted() throws Exception {
        String user = aUserWithPin(PIN);

        mvc.perform(verify(transaction(), user, "12")).andExpect(status().isBadRequest());
        mvc.perform(verify(transaction(), user, PIN)).andExpect(status().isOk());
    }

    @Test
    void walletIsNotAllowedToVerifyAPin() throws Exception {
        String user = aUserWithPin(PIN);

        mvc.perform(verify(TestTokens.serviceTokenAs("wallet-service"), user, PIN)).andExpect(status().isForbidden());
    }

    @Test
    void aUserTokenIsAuthenticatedButNotAllowed() throws Exception {
        // A valid user token is authenticated, and the rule for this path refuses it: 403, not 401 (wallet-plan 3.7).
        String user = aUserWithPin(PIN);

        mvc.perform(verify(TestTokens.userToken(user, "USER"), user, PIN)).andExpect(status().isForbidden());
    }

    @Test
    void thePinNeverReachesTheLogs() throws Exception {
        String user = aUserWithPin(PIN);
        mvc.perform(verify(transaction(), user, PIN)).andExpect(status().isOk());
        mvc.perform(verify(transaction(), user, "1111")).andExpect(status().isForbidden());

        assertFalse(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(PIN)
                || event.getFormattedMessage().contains("1111")), "no log line carries a PIN");
    }

    // ------------------------------------------------------------------ helpers

    private static String transaction() {
        return TestTokens.serviceTokenAs("transaction-service");
    }

    private String aUserWithPin(String pin) {
        String user = "user-" + UUID.randomUUID();
        credentials.saveAndFlush(new PinCredential(user, hasher.hash(user, pin)));
        return user;
    }

    private static MockHttpServletRequestBuilder verify(String token, String userId, String pin) {
        return post(PATH).header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"pin\":\"" + pin + "\"}");
    }
}
