package org.elibanking.authservice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.elibanking.authservice.entity.Role;
import org.elibanking.authservice.entity.User;
import org.elibanking.authservice.repository.UserRepository;
import org.elibanking.authservice.service.Concurrently;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * The internal password check that wallet-service uses to set the PIN (wallet-plan 3.5, threat model T7, T8, T4 and D-P10).
 * Real MySQL and Redis. The tests use fresh user ids, because the limit is per user.
 */
@SpringBootTest(properties = {"auth.jwt-secret=" + TestTokens.USER_SECRET,
        "internal-auth.jwt-secret=" + TestTokens.SERVICE_SECRET})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalAuthTest {

    private static final String PATH = "/internal/v1/auth/verify-password";
    private static final String PASSWORD = "the-right-password-1";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

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
    void noTokenIsRefused() throws Exception {
        mvc.perform(check(null, "someone", PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void aUserTokenIsNotAServiceToken() throws Exception {
        mvc.perform(check(TestTokens.userToken("someone", "USER"), "someone", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void transactionServiceIsNotAllowedToCheckPasswords() throws Exception {
        mvc.perform(check(TestTokens.serviceTokenAs("transaction-service"), "someone", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() throws Exception {
        mvc.perform(check(TestTokens.serviceTokenFor("account-service"), "someone", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void walletGetsTheVerdict() throws Exception {
        String user = aUser();

        mvc.perform(check(wallet(), user, PASSWORD)).andExpect(status().isOk())
                .andExpect(content().json("{\"valid\":true}"));
        mvc.perform(check(wallet(), user, "not-the-password")).andExpect(status().isOk())
                .andExpect(content().json("{\"valid\":false}"));
        mvc.perform(check(wallet(), "no-such-user-" + UUID.randomUUID(), PASSWORD)).andExpect(status().isOk())
                .andExpect(content().json("{\"valid\":false}"));
    }

    @Test
    void theSixthCheckInTheWindowIsRefusedEvenWithTheRightPassword() throws Exception {
        String user = aUser();
        for (int i = 0; i < 5; i++) {
            mvc.perform(check(wallet(), user, PASSWORD)).andExpect(status().isOk());
        }

        mvc.perform(check(wallet(), user, PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void tenParallelChecksGetFiveVerdictsAndFiveRefusals() throws Exception {
        String user = aUser();

        List<Object> outcomes = Concurrently.sameCall(10, () -> mvc.perform(check(wallet(), user, PASSWORD)).andReturn());

        long verdicts = outcomes.stream().filter(o -> o instanceof MvcResult r && r.getResponse().getStatus() == 200).count();
        long refusals = outcomes.stream().filter(o -> o instanceof MvcResult r && r.getResponse().getStatus() == 429).count();
        long valid = outcomes.stream().filter(o -> o instanceof MvcResult r
                && new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8).contains("\"valid\":true"))
                .count();
        assertEquals(5, verdicts, "exactly five calls were compared");
        assertEquals(5, refusals, "the other five were refused without a comparison");
        assertEquals(5, valid, "every comparison used the right password, so all five verdicts are valid");
    }

    @Test
    void everyVerdictAndRefusalIsAudited() throws Exception {
        String user = aUser();
        mvc.perform(check(wallet(), user, PASSWORD)).andExpect(status().isOk());
        mvc.perform(check(wallet(), user, "wrong")).andExpect(status().isOk());
        for (int i = 0; i < 3; i++) {
            mvc.perform(check(wallet(), user, PASSWORD)).andExpect(status().isOk());
        }
        // The sixth call is over the limit.
        mvc.perform(check(wallet(), user, PASSWORD)).andExpect(status().isTooManyRequests());

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'USER' AND entity_id = ?", Integer.class, user);
        Integer refused = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'PASSWORD_CHECK_LIMITED'",
                Integer.class, user);
        assertEquals(6, rows, "five verdicts and one refusal, one row each");
        assertEquals(1, refused);
        Integer rejected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'PASSWORD_REJECTED'", Integer.class, user);
        assertEquals(1, rejected);
    }

    @Test
    void thePasswordNeverReachesTheLogs() throws Exception {
        String user = aUser();
        mvc.perform(check(wallet(), user, PASSWORD)).andExpect(status().isOk());
        mvc.perform(check(wallet(), user, "a-wrong-password-value")).andExpect(status().isOk());

        boolean leaked = logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(PASSWORD)
                || event.getFormattedMessage().contains("a-wrong-password-value"));
        assertFalse(leaked, "no log line carries a password");
    }

    // ------------------------------------------------------------------ helpers

    private static String wallet() {
        return TestTokens.serviceTokenAs("wallet-service");
    }

    /** A new user with the test password. The id is generated, as in production, so the saved entity's id is used. */
    private String aUser() {
        User user = new User();
        user.setFullName("Test User");
        user.setEmail("user-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEmailVerified(true);
        user.setEnabled(true);
        return users.saveAndFlush(user).getId();
    }

    private static MockHttpServletRequestBuilder check(String token, String userId, String password) {
        MockHttpServletRequestBuilder request = post(PATH).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"password\":\"" + password + "\"}");
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }
}
