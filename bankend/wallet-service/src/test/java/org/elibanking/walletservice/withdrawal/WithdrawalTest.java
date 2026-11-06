package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.TestTokens;
import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.AccountCheck;
import org.elibanking.walletservice.client.AccountServiceClient.LedgerLine;
import org.elibanking.walletservice.client.AccountServiceClient.PostResult;
import org.elibanking.walletservice.client.FraudClient;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Resolution;
import org.elibanking.walletservice.enquiry.NameEnquiryService;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.elibanking.walletservice.pin.PinService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/wallet/withdrawals over HTTP, with real MySQL and Redis (wallet-plan 8.3). The account-service, payment-service and PIN
 * checks are mocked: each is another service's answer, and each test sets it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawalTest {

    private static final String PATH = "/api/v1/wallet/withdrawals";
    private static final String OTHER_BANK = "001";
    private static final String OWN_BANK = "999";

    @Autowired
    MockMvc mvc;

    @Autowired
    NameEnquiryService enquiries;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountServiceClient accounts;

    @MockitoBean
    PaymentServiceClient payments;

    @MockitoBean
    PinService pins;

    /** Mocked, so a test never reaches a real fraud service. The gate itself is tested in WithdrawalGateTest. */
    @MockitoBean
    FraudClient fraud;

    @Test
    void aWithdrawalIsHeldOnceAndAClearSmallOneIsApproved() throws Exception {
        Fixture f = new Fixture();
        f.accountIsActive(new BigDecimal("1000000.00"));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));

        MvcResult result = mvc.perform(f.withdraw(f.key(), "5000.00", OTHER_BANK))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.destinationAccountName").value("Bob Example"))
                .andReturn();
        long payoutId = payoutIdOf(result);

        ArgumentCaptor<List<LedgerLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(accounts).postEntry(eq("OUTBOUND_HOLD"), eq("out-" + payoutId + "-hold"), eq(String.valueOf(payoutId)), anyString(),
                lines.capture());
        assertEquals("5000.00", lines.getValue().get(0).amount(), "the wallet is debited the amount, and the fee (zero)");
        assertEquals("PAYOUT_PAYABLE:NGN", lines.getValue().get(1).systemAccount());
        assertEquals("5000.00", usage(f.account));
        assertEquals(1, auditRows(payoutId, "PAYOUT_REQUESTED"));
    }

    @Test
    void insufficientFundsFailsThePayoutAndGivesTheAllowanceBack() throws Exception {
        Fixture f = new Fixture();
        f.accountIsActive(new BigDecimal("1000000.00"));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RequestRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds on ledger account 7"));

        mvc.perform(f.withdraw(f.key(), "5000.00", OTHER_BANK))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Insufficient funds on ledger account 7"));

        assertEquals("FAILED", stateOfLatest(f.user));
        assertEquals("0.00", usage(f.account), "the failed payout gives its allowance back");
    }

    @Test
    void aWrongPinIsRefusedWithNothingOpened() throws Exception {
        Fixture f = new Fixture();
        f.accountIsActive(new BigDecimal("1000000.00"));
        when(pins.check(eq(f.user), anyString(), eq(f.user))).thenReturn(new PinService.Check(PinService.Outcome.WRONG, 0));

        mvc.perform(f.withdraw(f.key(), "5000.00", OTHER_BANK))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Wrong PIN"));

        assertEquals(0, payoutsFor(f.user));
        verify(accounts, never()).postEntry(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aWithdrawalOverTheDailyLimitIsRefusedWithNothingOpened() throws Exception {
        Fixture f = new Fixture();
        f.accountIsActive(new BigDecimal("3000.00"));

        mvc.perform(f.withdraw(f.key(), "5000.00", OTHER_BANK)).andExpect(status().isUnprocessableEntity());

        assertEquals(0, payoutsFor(f.user));
        verify(accounts, never()).postEntry(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void theSecondWithdrawalCountsAgainstTheSameDay() throws Exception {
        Fixture f = new Fixture();
        f.accountIsActive(new BigDecimal("6000.00"));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));

        mvc.perform(f.withdraw(f.key(), "5000.00", OTHER_BANK)).andExpect(status().isAccepted());
        mvc.perform(f.withdraw(f.key(), "2000.00", OTHER_BANK)).andExpect(status().isUnprocessableEntity());

        assertEquals("5000.00", usage(f.account), "only the first counts: 5000 + 2000 is over the 6000 limit");
    }

    @Test
    void anOwnBankDestinationIsRefusedAsATransfer() throws Exception {
        Fixture f = new Fixture();

        mvc.perform(f.withdraw(f.key(), "5000.00", OWN_BANK))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Use a transfer for an account at our bank"));

        verify(accounts, never()).check(anyString());
    }

    @Test
    void anAmountBelowTheMinimumIsRefused() throws Exception {
        Fixture f = new Fixture();

        mvc.perform(f.withdraw(f.key(), "50.00", OTHER_BANK)).andExpect(status().isUnprocessableEntity());

        verify(accounts, never()).check(anyString());
    }

    @Test
    void aNameEnquiryThatIsNotValidForThisDestinationIsRefused() throws Exception {
        Fixture f = new Fixture();
        f.accountIsActive(new BigDecimal("1000000.00"));

        mvc.perform(f.withdrawWithReference(f.key(), "not-a-reference", "5000.00", OTHER_BANK))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Name enquiry is invalid or expired"));

        assertEquals(0, payoutsFor(f.user));
    }

    @Test
    void aReplayReturnsTheStoredAnswerAndHoldsOnce() throws Exception {
        Fixture f = new Fixture();
        f.accountIsActive(new BigDecimal("1000000.00"));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));
        String key = f.key();

        long first = payoutIdOf(mvc.perform(f.withdraw(key, "5000.00", OTHER_BANK)).andExpect(status().isAccepted()).andReturn());
        long second = payoutIdOf(mvc.perform(f.withdraw(key, "5000.00", OTHER_BANK)).andExpect(status().isAccepted()).andReturn());

        assertEquals(first, second);
        verify(accounts, times(1)).postEntry(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aLedgerOutageLeavesThePayoutRequestedAndTheRetryHoldsItOnce() throws Exception {
        Fixture f = new Fixture();
        f.accountIsActive(new BigDecimal("1000000.00"));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "Accounts are unavailable right now."))
                .thenReturn(new PostResult(1L, false));
        String key = f.key();

        mvc.perform(f.withdraw(key, "5000.00", OTHER_BANK)).andExpect(status().isServiceUnavailable());
        assertEquals("REQUESTED", stateOfLatest(f.user), "the money is not held yet, and the payout waits");

        long payoutId = payoutIdOf(mvc.perform(f.withdraw(key, "5000.00", OTHER_BANK)).andExpect(status().isAccepted()).andReturn());

        assertEquals(1, payoutsFor(f.user), "the retry resumes the payout it opened");
        verify(accounts, times(2)).postEntry(eq("OUTBOUND_HOLD"), eq("out-" + payoutId + "-hold"), anyString(), anyString(),
                anyList());
    }

    // ------------------------------------------------------------------ helpers

    private long payoutIdOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body).get("payoutId").asLong();
    }

    private String usage(String account) {
        return jdbc.queryForObject("SELECT total FROM outbound_daily_usage WHERE account_number = ? AND usage_day = ?",
                BigDecimal.class, account, LocalDate.now(org.elibanking.walletservice.payout.PayoutTransitions.BUSINESS_ZONE))
                .setScale(2).toPlainString();
    }

    private int payoutsFor(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbound_payout WHERE user_id = ?", Integer.class, user);
    }

    private int auditRows(long payoutId, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'outbound_payout' AND entity_id = ?"
                + " AND action = ?", Integer.class, String.valueOf(payoutId), action);
    }

    private String stateOfLatest(String user) {
        return jdbc.queryForObject("SELECT state FROM outbound_payout WHERE user_id = ? ORDER BY id DESC LIMIT 1", String.class, user);
    }

    /** One user, one account, one valid name enquiry, and the request builder. */
    private class Fixture {
        final String user = "user-" + UUID.randomUUID();
        final String account = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        final String destination = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        final String ref;

        Fixture() {
            when(payments.resolveAccount(eq(OTHER_BANK), eq(destination))).thenReturn(new Resolution("FOUND", "Bob Example"));
            when(pins.check(eq(user), anyString(), eq(user))).thenReturn(new PinService.Check(PinService.Outcome.VALID, 0));
            when(fraud.check(anyString(), anyString(), any())).thenReturn(Optional.of(new FraudClient.FraudResult(false, null)));
            ref = enquiries.enquire(user, OTHER_BANK, destination).ref();
        }

        void accountIsActive(BigDecimal dailyLimit) {
            when(accounts.check(account)).thenReturn(new AccountCheck(user, "ACTIVE", "NGN"));
            when(accounts.dailyLimit(account)).thenReturn(dailyLimit);
        }

        String key() {
            return "wd-" + UUID.randomUUID();
        }

        MockHttpServletRequestBuilder withdraw(String key, String amount, String bank) {
            return withdrawWithReference(key, ref, amount, bank);
        }

        MockHttpServletRequestBuilder withdrawWithReference(String key, String reference, String amount, String bank) {
            return post(PATH)
                    .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER"))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountNumber\":\"" + account + "\",\"amount\":\"" + amount + "\",\"bankCode\":\"" + bank
                            + "\",\"destinationAccountNumber\":\"" + destination + "\",\"nameEnquiryRef\":\"" + reference
                            + "\",\"narration\":\"rent\",\"pin\":\"1234\"}");
        }
    }
}
