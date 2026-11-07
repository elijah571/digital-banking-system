package org.elibanking.walletservice.withdrawal;

import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.PostResult;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Send;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.elibanking.walletservice.payout.OutboundPayout;
import org.elibanking.walletservice.payout.OutboundPayoutRepository;
import org.elibanking.walletservice.payout.PayoutState;
import org.elibanking.walletservice.payout.PayoutTransitions;
import org.elibanking.walletservice.payout.PayoutType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payout processor (wallet-plan 8.3, step 5): each answer the provider can give moves the payout the way the plan says. The
 * provider and the ledger are mocked, and each test sets their answers.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PayoutProcessorTest {

    @Autowired
    PayoutProcessor processor;

    @Autowired
    OutboundPayoutRepository payouts;

    @Autowired
    PayoutTransitions transitions;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    PaymentServiceClient payments;

    @MockitoBean
    AccountServiceClient accounts;

    @BeforeEach
    void theLedgerAccepts() {
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));
    }

    @Test
    void aSendThatSucceedsIsSettledAndCompleted() {
        String account = newAccount();
        long id = approvedPayout(account, "5000.00");
        when(payments.sendPayout(eq("out-" + id), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new Send("SIM-" + id, "ACCEPTED"));
        when(payments.payoutStatus("out-" + id)).thenReturn("SUCCESS");

        processor.advance(id);
        assertEquals("PROCESSING", stateOf(id));
        processor.advance(id);

        assertEquals("COMPLETED", stateOf(id));
        verify(accounts, times(1)).postEntry(eq("OUTBOUND_SETTLE"), eq("out-" + id + "-settle"), anyString(), anyString(), anyList());
        assertEquals("5000.00", usage(account), "a completed payout keeps counting against the day");
    }

    @Test
    void aProviderThatRefusesTheSendReleasesTheMoneyAndFails() {
        String account = newAccount();
        long id = approvedPayout(account, "5000.00");
        when(payments.sendPayout(eq("out-" + id), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new Send("", "FAILED"));

        processor.advance(id);

        assertEquals("FAILED", stateOf(id));
        verify(accounts, times(1)).postEntry(eq("OUTBOUND_RELEASE"), eq("out-" + id + "-release"), anyString(), anyString(), anyList());
        assertEquals("0.00", usage(account), "a payout that failed gives its allowance back");
    }

    @Test
    void aPendingTransferStaysProcessingUntilTheProviderSaysSuccess() {
        long id = approvedPayout(newAccount(), "5000.00");
        when(payments.sendPayout(eq("out-" + id), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new Send("SIM-" + id, "PENDING"));
        when(payments.payoutStatus("out-" + id)).thenReturn("PENDING", "SUCCESS");

        processor.advance(id);
        processor.advance(id);
        assertEquals("PROCESSING", stateOf(id), "the provider has not answered yet, so nothing is settled");

        processor.advance(id);
        assertEquals("COMPLETED", stateOf(id));
    }

    @Test
    void aTransferTheProviderDoesNotKnowIsSentAgainUnderTheSameKey() {
        long id = approvedPayout(newAccount(), "5000.00");
        when(payments.sendPayout(eq("out-" + id), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new Send("SIM-" + id, "ACCEPTED"));
        when(payments.payoutStatus("out-" + id)).thenReturn("NOT_FOUND");

        processor.advance(id);
        processor.advance(id);

        verify(payments, times(2)).sendPayout(eq("out-" + id), anyString(), anyString(), anyString(), any(), any());
        assertEquals("PROCESSING", stateOf(id));
    }

    @Test
    void recoveryAfterSendChecksStatusBeforeRetryingAndSettlesOnlyOnSuccess() {
        long id = approvedPayout(newAccount(), "5000.00");
        tx.executeWithoutResult(status -> transitions.move(payouts.findById(id).orElseThrow(), PayoutState.PROCESSING,
                "send may have reached the provider before the worker stopped"));
        when(payments.payoutStatus("out-" + id)).thenReturn("SUCCESS");

        processor.advance(id);

        assertEquals("COMPLETED", stateOf(id));
        verify(payments, times(1)).payoutStatus("out-" + id);
        verify(payments, never()).sendPayout(anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(accounts, times(1)).postEntry(eq("OUTBOUND_SETTLE"), eq("out-" + id + "-settle"), anyString(), anyString(), anyList());
    }

    @Test
    void aReversalBeforeSettleReleasesWithoutSettling() {
        String account = newAccount();
        long id = approvedPayout(account, "5000.00");
        when(payments.sendPayout(eq("out-" + id), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new Send("SIM-" + id, "ACCEPTED"));
        when(payments.payoutStatus("out-" + id)).thenReturn("REVERSED");

        processor.advance(id);
        processor.advance(id);

        assertEquals("REVERSED", stateOf(id));
        verify(accounts, times(1)).postEntry(eq("OUTBOUND_RELEASE"), eq("out-" + id + "-release"), anyString(), anyString(), anyList());
        verify(accounts, never()).postEntry(eq("OUTBOUND_SETTLE"), anyString(), anyString(), anyString(), anyList());
        assertEquals("0.00", usage(account));
    }

    @Test
    void aSendThatCannotBeReachedIsSentAgainByTheNextRun() {
        long id = approvedPayout(newAccount(), "5000.00");
        when(payments.sendPayout(eq("out-" + id), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "down"))
                .thenReturn(new Send("SIM-" + id, "ACCEPTED"));
        when(payments.payoutStatus("out-" + id)).thenReturn("NOT_FOUND");

        assertThrows(RequestRefusedException.class, () -> processor.advance(id));
        assertEquals("PROCESSING", stateOf(id), "the move is kept, so the next run finds it and sends it again");

        processor.advance(id);

        assertEquals("SIM-" + id, jdbc.queryForObject("SELECT provider_reference FROM outbound_payout WHERE id = ?", String.class, id));
    }

    @Test
    void aCompletedPayoutTheBankReversesIsReversedWithItsEntryAndFeeRefund() {
        String account = newAccount();
        long id = approvedPayout(account, "5000.00");
        when(payments.sendPayout(eq("out-" + id), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new Send("SIM-" + id, "ACCEPTED"));
        when(payments.payoutStatus("out-" + id)).thenReturn("SUCCESS", "REVERSED");
        processor.advance(id);
        processor.advance(id);
        assertEquals("COMPLETED", stateOf(id));

        boolean reversed = processor.reverseIfReversed(id);

        assertEquals(true, reversed);
        assertEquals("REVERSED", stateOf(id));
        verify(accounts, times(1)).postEntry(eq("OUTBOUND_REVERSAL"), eq("out-" + id + "-reversal"), anyString(), anyString(), anyList());
        assertEquals("0.00", usage(account), "D21: a reversed payout gives its allowance back");
    }

    @Test
    void aPayoutThatIsNotApprovedIsLeftAlone() {
        OutboundPayout payout = new OutboundPayout();
        payout.setType(PayoutType.WITHDRAWAL);
        payout.setUserId("user-" + newAccount());
        payout.setIdempotencyKey("test-" + newAccount());
        payout.setAccountNumber(newAccount());
        payout.setAmount(new BigDecimal("5000.00"));
        payout.setFee(BigDecimal.ZERO);
        payout.setCurrency("NGN");
        payout.setBankCode("001");
        payout.setDestinationAccountNumber("0987654321");
        payout.setState(PayoutState.REQUESTED);
        payout.setCreatedAt(Instant.now());
        payout.setUpdatedAt(payout.getCreatedAt());
        long id = payouts.save(payout).getId();

        processor.advance(id);

        assertEquals("REQUESTED", stateOf(id), "only an approved payout is sent");
        verify(payments, never()).sendPayout(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    /** A payout that is APPROVED, with its day's usage counted, as the request flow leaves it. */
    private long approvedPayout(String account, String amount) {
        OutboundPayout payout = new OutboundPayout();
        payout.setType(PayoutType.WITHDRAWAL);
        payout.setUserId("user-" + account);
        payout.setIdempotencyKey("test-" + account);
        payout.setAccountNumber(account);
        payout.setAmount(new BigDecimal(amount));
        payout.setFee(BigDecimal.ZERO);
        payout.setCurrency("NGN");
        payout.setBankCode("001");
        payout.setDestinationAccountNumber("0987654321");
        payout.setDestinationAccountName("Bob Example");
        payout.setState(PayoutState.REQUESTED);
        payout.setCreatedAt(Instant.now());
        payout.setUpdatedAt(payout.getCreatedAt());
        long id = payouts.save(payout).getId();
        jdbc.update("INSERT INTO outbound_daily_usage (account_number, usage_day, total) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE total = total + VALUES(total)",
                account, LocalDate.ofInstant(payout.getCreatedAt(), PayoutTransitions.BUSINESS_ZONE), new BigDecimal(amount));
        tx.executeWithoutResult(s -> transitions.move(payouts.findById(id).orElseThrow(), PayoutState.APPROVED, "approved"));
        return id;
    }

    private String stateOf(long id) {
        return jdbc.queryForObject("SELECT state FROM outbound_payout WHERE id = ?", String.class, id);
    }

    private String usage(String account) {
        return jdbc.queryForObject("SELECT total FROM outbound_daily_usage WHERE account_number = ? AND usage_day = ?",
                BigDecimal.class, account, LocalDate.now(PayoutTransitions.BUSINESS_ZONE)).setScale(2).toPlainString();
    }

    private static String newAccount() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }
}
