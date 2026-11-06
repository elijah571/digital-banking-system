package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.AccountCheck;
import org.elibanking.walletservice.client.AccountServiceClient.PostResult;
import org.elibanking.walletservice.client.FraudClient;
import org.elibanking.walletservice.client.FraudClient.FraudResult;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Resolution;
import org.elibanking.walletservice.enquiry.NameEnquiryService;
import org.elibanking.walletservice.pin.PinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The approval gate and the decisions on a withdrawal (wallet-plan 8.3), over HTTP with real MySQL. The threshold in tests is 100000.00
 * naira. The fraud check and the ledger are mocked, and each test sets what they answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawalGateTest {

    private static final String OTHER_BANK = "001";

    @Autowired
    MockMvc mvc;

    @Autowired
    NameEnquiryService enquiries;

    @Autowired
    WithdrawalExpiryService expiry;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountServiceClient accounts;

    @MockitoBean
    PaymentServiceClient payments;

    @MockitoBean
    PinService pins;

    @MockitoBean
    FraudClient fraud;

    @BeforeEach
    void theLedgerAccepts() {
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));
    }

    @Test
    void aClearPayoutBelowTheThresholdIsApprovedOnItsOwn() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.of(new FraudResult(false, null)));

        long id = payoutIdOf(mvc.perform(r.withdraw("5000.00")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("APPROVED")).andReturn());

        assertEquals(0, approvalEvents(id), "no one is asked to approve a payout that was approved on its own");
    }

    @Test
    void aPayoutAtOrAboveTheThresholdWaitsForAnAdmin() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.of(new FraudResult(false, null)));

        long id = payoutIdOf(mvc.perform(r.withdraw("150000.00")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL")).andReturn());

        assertEquals(1, approvalEvents(id), "the approval-required event goes out with the change");
    }

    @Test
    void aFlaggedPayoutWaitsForAnAdmin() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any()))
                .thenReturn(Optional.of(new FraudResult(true, "Too many transactions in 60 seconds")));

        mvc.perform(r.withdraw("5000.00")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void aFraudCheckThatDoesNotAnswerFailsClosedToAPendingApproval() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.empty());

        mvc.perform(r.withdraw("5000.00")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void aRequesterCannotApproveTheirOwnPayout() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.empty());
        long id = payoutIdOf(mvc.perform(r.withdraw("5000.00")).andReturn());

        mvc.perform(decide(TestTokens.userToken(r.user, "ADMIN"), id, "approve", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("The requester cannot approve their own withdrawal"));
    }

    @Test
    void anotherAdminApprovesAPayoutAndAUserCannot() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.empty());
        long id = payoutIdOf(mvc.perform(r.withdraw("5000.00")).andReturn());

        mvc.perform(decide(TestTokens.userToken(UUID.randomUUID().toString(), "USER"), id, "approve", null))
                .andExpect(status().isForbidden());
        mvc.perform(decide(TestTokens.userToken("admin-" + UUID.randomUUID(), "ADMIN"), id, "approve", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void aRejectionReleasesTheMoneyAndGivesTheAllowanceBack() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.empty());
        long id = payoutIdOf(mvc.perform(r.withdraw("5000.00")).andReturn());

        mvc.perform(decide(TestTokens.userToken("admin-" + UUID.randomUUID(), "ADMIN"), id, "reject",
                        "{\"reason\":\"not this one\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(accounts, times(1)).postEntry(eq("OUTBOUND_RELEASE"), eq("out-" + id + "-release"), anyString(), anyString(), anyList());
        assertEquals("0.00", usage(r.account), "a rejection gives the allowance back");
    }

    @Test
    void theOwnerCancelsTheirPendingPayoutAndAnotherUserGetsNotFound() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.empty());
        long id = payoutIdOf(mvc.perform(r.withdraw("5000.00")).andReturn());

        mvc.perform(post("/api/v1/wallet/withdrawals/" + id + "/cancel")
                        .header("Authorization", "Bearer " + TestTokens.userToken(newUser(), "USER")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/wallet/withdrawals/" + id + "/cancel")
                        .header("Authorization", "Bearer " + TestTokens.userToken(r.user, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(accounts, times(1)).postEntry(eq("OUTBOUND_RELEASE"), eq("out-" + id + "-release"), anyString(), anyString(), anyList());
    }

    @Test
    void aPayoutNoOneDecidesInTimeIsExpiredAndReleased() throws Exception {
        Requester r = new Requester();
        r.accountIsActive();
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.empty());
        long id = payoutIdOf(mvc.perform(r.withdraw("5000.00")).andReturn());
        jdbc.update("UPDATE outbound_payout SET updated_at = DATE_SUB(NOW(6), INTERVAL 4 DAY) WHERE id = ?", id);

        expiry.expireDue();

        assertEquals("EXPIRED", stateOf(id));
        verify(accounts, times(1)).postEntry(eq("OUTBOUND_RELEASE"), eq("out-" + id + "-release"), anyString(), anyString(), anyList());
    }

    // ------------------------------------------------------------------ helpers

    private long payoutIdOf(MvcResult result) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("payoutId").asLong();
    }

    private MockHttpServletRequestBuilder decide(String token, long id, String action, String body) {
        MockHttpServletRequestBuilder request = post("/api/v1/wallet/withdrawals/" + id + "/" + action)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request;
    }

    private int approvalEvents(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE message_key = ? AND topic = 'wallet.payout.approval-required'",
                Integer.class, "out-" + id);
    }

    private String usage(String account) {
        return jdbc.queryForObject("SELECT total FROM outbound_daily_usage WHERE account_number = ? AND usage_day = ?",
                BigDecimal.class, account, java.time.LocalDate.now(org.elibanking.walletservice.payout.PayoutTransitions.BUSINESS_ZONE))
                .setScale(2).toPlainString();
    }

    private String stateOf(long id) {
        return jdbc.queryForObject("SELECT state FROM outbound_payout WHERE id = ?", String.class, id);
    }

    private static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    /** A user with one account and one valid name enquiry, who can ask for a withdrawal. */
    private class Requester {
        final String user = newUser();
        final String account = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        final String destination = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        final String ref;

        Requester() {
            when(payments.resolveAccount(eq(OTHER_BANK), eq(destination))).thenReturn(new Resolution("FOUND", "Bob Example"));
            when(pins.check(eq(user), anyString(), eq(user))).thenReturn(new PinService.Check(PinService.Outcome.VALID, 0));
            ref = enquiries.enquire(user, OTHER_BANK, destination).ref();
        }

        void accountIsActive() {
            when(accounts.check(account)).thenReturn(new AccountCheck(user, "ACTIVE", "NGN"));
            when(accounts.dailyLimit(account)).thenReturn(new BigDecimal("1000000.00"));
        }

        MockHttpServletRequestBuilder withdraw(String amount) {
            return post("/api/v1/wallet/withdrawals")
                    .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER"))
                    .header("Idempotency-Key", "wd-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountNumber\":\"" + account + "\",\"amount\":\"" + amount + "\",\"bankCode\":\"" + OTHER_BANK
                            + "\",\"destinationAccountNumber\":\"" + destination + "\",\"nameEnquiryRef\":\"" + ref
                            + "\",\"pin\":\"1234\"}");
        }
    }
}
