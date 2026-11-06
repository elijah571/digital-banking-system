package org.elibanking.walletservice.deposit;

import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.AccountCheck;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Intent;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/wallet/deposits over HTTP, with real MySQL and the real security chain. account-service and payment-service are
 * mocked: they are other services, and each test sets what they answer (wallet-plan 3.5, 3.6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DepositControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    AccountServiceClient accounts;

    @MockitoBean
    PaymentServiceClient payments;

    @Test
    void aDepositIsOpenedPendingWithItsIntentAndAnAuditRow() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(accounts.check(account)).thenReturn(new AccountCheck(user, "ACTIVE", "NGN"));
        when(payments.createIntent(anyString(), eq("20000.00"), eq("NGN")))
                .thenReturn(new Intent("pi_test_open", "pi_test_open_secret"));

        MvcResult result = mvc.perform(deposit(user, newKey(), account, "20000.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value("20000.00"))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.paymentIntentId").value("pi_test_open"))
                .andExpect(jsonPath("$.clientSecret").value("pi_test_open_secret"))
                .andReturn();
        String depositId = json.readTree(result.getResponse().getContentAsString()).get("depositId").asText();

        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM deposit WHERE id = ?", String.class, depositId));
        assertEquals("pi_test_open", jdbc.queryForObject(
                "SELECT payment_intent_id FROM deposit WHERE id = ?", String.class, depositId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'deposit' AND entity_id = ? AND action = 'DEPOSIT_REQUESTED'",
                Integer.class, depositId));
        verify(payments).createIntent(depositId, "20000.00", "NGN");
    }

    @Test
    void aReplayReturnsTheStoredResponseAndOpensNothingNew() throws Exception {
        String user = newUser();
        String account = newAccount();
        String key = newKey();
        when(accounts.check(account)).thenReturn(new AccountCheck(user, "ACTIVE", "NGN"));
        when(payments.createIntent(anyString(), anyString(), anyString()))
                .thenReturn(new Intent("pi_test_replay", "pi_test_replay_secret"));

        String first = mvc.perform(deposit(user, key, account, "25000.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(deposit(user, key, account, "25000.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertEquals(json.readTree(first).get("depositId").asText(), json.readTree(second).get("depositId").asText());
        assertEquals(1, depositsFor(user));
        verify(payments, times(1)).createIntent(anyString(), anyString(), anyString());
    }

    @Test
    void aSameKeyWithADifferentBodyIsRefused() throws Exception {
        String user = newUser();
        String account = newAccount();
        String key = newKey();
        when(accounts.check(account)).thenReturn(new AccountCheck(user, "ACTIVE", "NGN"));
        when(payments.createIntent(anyString(), anyString(), anyString()))
                .thenReturn(new Intent("pi_test_mismatch", "pi_test_mismatch_secret"));
        mvc.perform(deposit(user, key, account, "25000.00")).andExpect(status().isCreated());

        mvc.perform(deposit(user, key, account, "26000.00")).andExpect(status().isUnprocessableEntity());

        assertEquals(1, depositsFor(user));
    }

    @Test
    void anAmountBelowTheMinimumIsRefusedBeforeAnythingIsChecked() throws Exception {
        String user = newUser();

        mvc.perform(deposit(user, newKey(), newAccount(), "19999.99")).andExpect(status().isBadRequest());

        assertEquals(0, depositsFor(user));
        verifyNoInteractions(accounts, payments);
    }

    @Test
    void anotherUsersAccountIsRefusedWithNothingOpened() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(accounts.check(account)).thenReturn(new AccountCheck("someone-else", "ACTIVE", "NGN"));

        mvc.perform(deposit(user, newKey(), account, "20000.00")).andExpect(status().isForbidden());

        assertEquals(0, depositsFor(user));
        verifyNoInteractions(payments);
    }

    @Test
    void aBlockedAccountIsRefusedWithNothingOpened() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(accounts.check(account)).thenReturn(new AccountCheck(user, "BLOCKED", "NGN"));

        mvc.perform(deposit(user, newKey(), account, "20000.00")).andExpect(status().isUnprocessableEntity());

        assertEquals(0, depositsFor(user));
        verifyNoInteractions(payments);
    }

    @Test
    void anUnknownAccountIsNotFound() throws Exception {
        String user = newUser();
        String account = newAccount();
        when(accounts.check(account)).thenThrow(new RequestRefusedException(HttpStatus.NOT_FOUND, "Account not found"));

        mvc.perform(deposit(user, newKey(), account, "20000.00")).andExpect(status().isNotFound());

        assertEquals(0, depositsFor(user));
    }

    @Test
    void aRequestWithoutAnIdempotencyKeyIsRefused() throws Exception {
        String user = newUser();

        mvc.perform(post("/api/v1/wallet/deposits")
                        .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"" + newAccount() + "\",\"amount\":\"20000.00\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(0, depositsFor(user));
    }

    @Test
    void aDownPaymentServiceRefusesAndTheRetryResumesTheSameDeposit() throws Exception {
        String user = newUser();
        String account = newAccount();
        String key = newKey();
        when(accounts.check(account)).thenReturn(new AccountCheck(user, "ACTIVE", "NGN"));
        when(payments.createIntent(anyString(), anyString(), anyString()))
                .thenThrow(new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "Card payments are unavailable right now."))
                .thenReturn(new Intent("pi_test_resumed", "pi_test_resumed_secret"));

        mvc.perform(deposit(user, key, account, "20000.00")).andExpect(status().isServiceUnavailable());
        assertEquals(1, depositsFor(user));
        String openedId = jdbc.queryForObject("SELECT id FROM deposit WHERE user_id = ?", String.class, user);

        MvcResult retry = mvc.perform(deposit(user, key, account, "20000.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentIntentId").value("pi_test_resumed"))
                .andReturn();

        assertEquals(openedId, json.readTree(retry.getResponse().getContentAsString()).get("depositId").asText());
        assertEquals(1, depositsFor(user), "the retry resumes the deposit it opened, and opens no second one");
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder deposit(String user, String key, String account, String amount) {
        return post("/api/v1/wallet/deposits")
                .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"" + account + "\",\"amount\":\"" + amount + "\"}");
    }

    private int depositsFor(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM deposit WHERE user_id = ?", Integer.class, user);
    }

    private static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    /** A ten-digit account number, as the request pattern requires. */
    private static String newAccount() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }
}
