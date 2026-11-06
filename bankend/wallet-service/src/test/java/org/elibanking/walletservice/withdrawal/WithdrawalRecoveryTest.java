package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.Concurrently;
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
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.elibanking.walletservice.payout.PayoutTransitions;
import org.elibanking.walletservice.pin.PinService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
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

/**
 * Recovery of a stuck payout, and the daily limit under parallel requests (wallet-plan 8.3), over HTTP with real MySQL. The ledger, the
 * fraud check and the provider are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawalRecoveryTest {

    private static final String OTHER_BANK = "001";

    @Autowired
    MockMvc mvc;

    @Autowired
    NameEnquiryService enquiries;

    @Autowired
    PayoutProcessor processor;

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

    @Test
    void aPayoutStuckInRequestedIsHeldAgainAndGatedByRecovery() throws Exception {
        Requester r = new Requester(new BigDecimal("1000000.00"));
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.of(new FraudResult(false, null)));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "Accounts are unavailable right now."))
                .thenReturn(new PostResult(1L, false));

        mvc.perform(r.withdraw(UUID.randomUUID().toString(), "5000.00")).andExpect(org.springframework.test.web.servlet.result
                .MockMvcResultMatchers.status().isServiceUnavailable());
        long id = payoutId(r.user);
        assertEquals("REQUESTED", stateOf(id));
        jdbc.update("UPDATE outbound_payout SET updated_at = DATE_SUB(NOW(6), INTERVAL 10 MINUTE) WHERE id = ?", id);

        processor.processDue();

        assertEquals("APPROVED", stateOf(id), "recovery held the money and the gate approved it");
        verify(accounts, times(2)).postEntry(eq("OUTBOUND_HOLD"), eq("out-" + id + "-hold"), anyString(), anyString(), anyList());
    }

    @Test
    void aRecentRequestedPayoutIsLeftForItsOwnRequest() throws Exception {
        Requester r = new Requester(new BigDecimal("1000000.00"));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "Accounts are unavailable right now."));

        mvc.perform(r.withdraw(UUID.randomUUID().toString(), "5000.00"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
        long id = payoutId(r.user);

        processor.processDue();

        assertEquals("REQUESTED", stateOf(id), "its request may still be running, so recovery waits");
        verify(accounts, times(1)).postEntry(eq("OUTBOUND_HOLD"), eq("out-" + id + "-hold"), anyString(), anyString(), anyList());
    }

    @Test
    void parallelWithdrawalsCannotGoOverTheDailyLimit() throws Exception {
        Requester r = new Requester(new BigDecimal("5000.00"));
        when(fraud.check(eq("WITHDRAWAL"), eq(r.account), any())).thenReturn(Optional.of(new FraudResult(false, null)));
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));

        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> mvc.perform(r.withdraw(UUID.randomUUID().toString(), "1000.00")).andReturn().getResponse().getStatus());
        }
        List<Object> answers = Concurrently.together(calls);

        long accepted = answers.stream().filter(answer -> Integer.valueOf(202).equals(answer)).count();
        long refused = answers.stream().filter(answer -> Integer.valueOf(422).equals(answer)).count();
        assertEquals(5, accepted, "the limit of 5000 allows five payouts of 1000; the answers were " + answers);
        assertEquals(7, refused, "the other seven are refused for the daily limit");
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM outbound_payout WHERE user_id = ?", Integer.class, r.user));
        assertEquals("5000.00", jdbc.queryForObject("SELECT total FROM outbound_daily_usage WHERE account_number = ? AND usage_day = ?",
                BigDecimal.class, r.account, LocalDate.now(PayoutTransitions.BUSINESS_ZONE)).setScale(2).toPlainString());
    }

    private long payoutId(String user) {
        return jdbc.queryForObject("SELECT id FROM outbound_payout WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, user);
    }

    private String stateOf(long id) {
        return jdbc.queryForObject("SELECT state FROM outbound_payout WHERE id = ?", String.class, id);
    }

    /** A user with one account, a daily limit, and a valid name enquiry, who can ask for withdrawals. */
    private class Requester {
        final String user = "user-" + UUID.randomUUID();
        final String account = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        final String destination = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        final String ref;

        Requester(BigDecimal dailyLimit) {
            when(payments.resolveAccount(eq(OTHER_BANK), eq(destination))).thenReturn(new Resolution("FOUND", "Bob Example"));
            when(pins.check(eq(user), anyString(), eq(user))).thenReturn(new PinService.Check(PinService.Outcome.VALID, 0));
            when(accounts.check(account)).thenReturn(new AccountCheck(user, "ACTIVE", "NGN"));
            when(accounts.dailyLimit(account)).thenReturn(dailyLimit);
            ref = enquiries.enquire(user, OTHER_BANK, destination).ref();
        }

        MockHttpServletRequestBuilder withdraw(String key, String amount) {
            return post("/api/v1/wallet/withdrawals")
                    .header("Authorization", "Bearer " + TestTokens.userToken(user, "USER"))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountNumber\":\"" + account + "\",\"amount\":\"" + amount + "\",\"bankCode\":\"" + OTHER_BANK
                            + "\",\"destinationAccountNumber\":\"" + destination + "\",\"nameEnquiryRef\":\"" + ref
                            + "\",\"pin\":\"1234\"}");
        }
    }
}
