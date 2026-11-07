package org.elibanking.walletservice.deposit;

import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.AccountServiceClient;
import org.elibanking.walletservice.client.AccountServiceClient.LedgerLine;
import org.elibanking.walletservice.client.AccountServiceClient.PostResult;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.elibanking.walletservice.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The settlement of a card deposit from payment-service's events (wallet-plan 3.5 steps 4 and 5, D26), with real MySQL. The ledger
 * and Kafka are mocked. The listener is not started, so each test calls the settlement directly.
 */
@SpringBootTest(properties = {"outbox.relay.enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import(TestcontainersConfiguration.class)
class DepositSettlementTest {

    private static final String SUCCEEDED = "payment.intent.succeeded";
    private static final String FAILED = "payment.intent.failed";

    @Autowired
    DepositSettlementService settlement;

    @Autowired
    DepositRepository deposits;

    @Autowired
    OutboxRelay relay;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    AccountServiceClient accounts;

    @MockitoBean
    KafkaTemplate<String, Object> kafka;

    @BeforeEach
    void theLedgerAccepts() {
        when(accounts.postEntry(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new PostResult(1L, false));
    }

    @Test
    void aSuccessCompletesTheDepositAndCreditsItOnce() throws Exception {
        Deposit deposit = openDeposit("20000.00");
        String evt = newEventId();

        settlement.apply(outcome(SUCCEEDED, evt, deposit.getId(), "20000.00", "NGN", null));

        assertEquals("COMPLETED", statusOf(deposit.getId()));
        verify(accounts, times(1)).postEntry(eq("DEPOSIT_CREDIT"), eq("dep-" + deposit.getId() + "-credit"),
                eq(deposit.getId()), eq(evt), anyList());
        assertEquals(1, outboxRows(deposit.getId(), "wallet.deposit.completed"));
        assertEquals(1, processedEvents(evt));
    }

    @Test
    void aRedeliveredEventIsAppliedOnce() throws Exception {
        Deposit deposit = openDeposit("20000.00");
        String message = outcome(SUCCEEDED, newEventId(), deposit.getId(), "20000.00", "NGN", null);

        settlement.apply(message);
        settlement.apply(message);

        verify(accounts, times(1)).postEntry(eq("DEPOSIT_CREDIT"), eq("dep-" + deposit.getId() + "-credit"),
                anyString(), anyString(), anyList());
        assertEquals(1, outboxRows(deposit.getId(), "wallet.deposit.completed"));
    }

    @Test
    void aSurplusCreditsTheRequestedAmountAndBooksTheRestToSuspense() throws Exception {
        Deposit deposit = openDeposit("20000.00");

        settlement.apply(outcome(SUCCEEDED, newEventId(), deposit.getId(), "20500.00", "NGN", null));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(accounts, times(2)).postEntry(anyString(), keys.capture(), anyString(), anyString(), lines.capture());
        assertEquals(List.of("dep-" + deposit.getId() + "-credit", "dep-" + deposit.getId() + "-suspense"), keys.getAllValues());
        assertEquals("20000.00", lines.getAllValues().get(0).get(1).amount(), "the credit is the requested amount only");
        assertEquals("500.00", lines.getAllValues().get(1).get(1).amount(), "the surplus goes to suspense");
        assertEquals("COMPLETED", statusOf(deposit.getId()));
        assertEquals(1, auditRows(deposit.getId(), "DEPOSIT_ALERT"), "a surplus raises an alert");
    }

    @Test
    void aShortfallCreditsNothingBooksTheReceiptAndFailsTheDeposit() throws Exception {
        Deposit deposit = openDeposit("20000.00");

        settlement.apply(outcome(SUCCEEDED, newEventId(), deposit.getId(), "19000.00", "NGN", null));

        verify(accounts, never()).postEntry(eq("DEPOSIT_CREDIT"), anyString(), anyString(), anyString(), anyList());
        verify(accounts, times(1)).postEntry(eq("DEPOSIT_SUSPENSE"), eq("dep-" + deposit.getId() + "-suspense"),
                anyString(), anyString(), anyList());
        assertEquals("FAILED", statusOf(deposit.getId()));
        assertEquals(1, outboxRows(deposit.getId(), "wallet.deposit.failed"));
        assertEquals(1, auditRows(deposit.getId(), "DEPOSIT_ALERT"), "a shortfall raises an alert");
    }

    @Test
    void aFailedPaymentFailsTheDepositWithNoLedgerEntry() throws Exception {
        Deposit deposit = openDeposit("20000.00");

        settlement.apply(outcome(FAILED, newEventId(), deposit.getId(), "0.00", "NGN", "Card declined"));

        assertEquals("FAILED", statusOf(deposit.getId()));
        assertEquals("Card declined", jdbc.queryForObject(
                "SELECT failure_reason FROM deposit WHERE id = ?", String.class, deposit.getId()));
        verifyNoInteractions(accounts);
    }

    @Test
    void aLateSuccessForAnExpiredDepositGoesToSuspenseAndTheDepositStaysExpired() throws Exception {
        Deposit deposit = newDeposit("20000.00");
        deposit.setStatus(DepositStatus.EXPIRED);
        deposits.save(deposit);
        String evt = newEventId();

        settlement.apply(outcome(SUCCEEDED, evt, deposit.getId(), "20000.00", "NGN", null));

        assertEquals("EXPIRED", statusOf(deposit.getId()));
        verify(accounts, never()).postEntry(eq("DEPOSIT_CREDIT"), anyString(), anyString(), anyString(), anyList());
        verify(accounts, times(1)).postEntry(eq("DEPOSIT_SUSPENSE"), eq("dep-" + deposit.getId() + "-suspense-" + evt),
                anyString(), anyString(), anyList());
        assertEquals(1, auditRows(deposit.getId(), "DEPOSIT_ALERT"));
    }

    @Test
    void aSuccessForAnUnknownDepositGoesToSuspenseAndAlerts() throws Exception {
        String unknown = UUID.randomUUID().toString();
        String evt = newEventId();

        settlement.apply(outcome(SUCCEEDED, evt, unknown, "20000.00", "NGN", null));

        verify(accounts, times(1)).postEntry(eq("DEPOSIT_SUSPENSE"), eq("dep-" + unknown + "-suspense-" + evt),
                anyString(), anyString(), anyList());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM deposit WHERE id = ?", Integer.class, unknown));
        assertEquals(1, auditRows(unknown, "DEPOSIT_ALERT"));
    }

    @Test
    void aReceiptInAnotherCurrencyIsNotPostedAndTheDepositFails() throws Exception {
        Deposit deposit = openDeposit("20000.00");
        String evt = newEventId();

        String message = outcome(SUCCEEDED, evt, deposit.getId(), "20000.00", "USD", null);
        settlement.apply(message);

        ArgumentCaptor<String> providerReference = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(accounts).quarantineReceipt(eq("STRIPE"), providerReference.capture(), eq(deposit.getId()),
                eq("CURRENCY_MISMATCH"), eq("USD"), eq("20000.00"), receipt.capture());
        assertTrue(providerReference.getValue().startsWith("pi_"));
        assertEquals("usd", mapper.readTree(receipt.getValue()).path("currency").asString());
        verify(accounts, never()).postEntry(anyString(), anyString(), anyString(), anyString(), anyList());
        assertEquals("FAILED", statusOf(deposit.getId()));
        assertEquals(1, auditRows(deposit.getId(), "DEPOSIT_ALERT"));
    }

    @Test
    void aQuarantineOutageLeavesTheDepositPendingUntilTheReceiptIsRecorded() throws Exception {
        Deposit deposit = openDeposit("20000.00");
        String evt = newEventId();
        String message = outcome(SUCCEEDED, evt, deposit.getId(), "20000.00", "USD", null);
        when(accounts.quarantineReceipt(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString()))
                .thenThrow(new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "down"))
                .thenReturn(false);

        assertThrows(RequestRefusedException.class, () -> settlement.apply(message));
        assertEquals("PENDING", statusOf(deposit.getId()));
        assertEquals(0, processedEvents(evt));

        settlement.apply(message);

        assertEquals("FAILED", statusOf(deposit.getId()));
        assertEquals(1, processedEvents(evt));
        verify(accounts, times(2)).quarantineReceipt(eq("STRIPE"), anyString(), eq(deposit.getId()),
                eq("CURRENCY_MISMATCH"), eq("USD"), eq("20000.00"), anyString());
    }

    @Test
    void aLedgerOutageLeavesTheDepositPendingUntilTheRetryApplies() throws Exception {
        Deposit deposit = openDeposit("20000.00");
        String evt = newEventId();
        String message = outcome(SUCCEEDED, evt, deposit.getId(), "20000.00", "NGN", null);
        when(accounts.postEntry(eq("DEPOSIT_CREDIT"), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "down"))
                .thenReturn(new PostResult(2L, false));

        assertThrows(RequestRefusedException.class, () -> settlement.apply(message));
        assertEquals("PENDING", statusOf(deposit.getId()), "the failed attempt changed nothing");
        assertEquals(0, processedEvents(evt), "the failed attempt did not claim the event");

        settlement.apply(message);

        assertEquals("COMPLETED", statusOf(deposit.getId()));
        assertEquals(1, processedEvents(evt));
        verify(accounts, times(2)).postEntry(eq("DEPOSIT_CREDIT"), eq("dep-" + deposit.getId() + "-credit"),
                anyString(), anyString(), anyList());
    }

    @Test
    void aCompletedDepositIsPublishedOnceAndMarkedSent() throws Exception {
        Deposit deposit = openDeposit("20000.00");
        settlement.apply(outcome(SUCCEEDED, newEventId(), deposit.getId(), "20000.00", "NGN", null));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        verify(kafka, times(1)).send(eq("wallet.deposit.completed"), eq(deposit.getId()), any());
        assertNotNull(jdbc.queryForObject(
                "SELECT sent_at FROM outbox WHERE aggregate_id = ? AND topic = 'wallet.deposit.completed'",
                java.sql.Timestamp.class, deposit.getId()));
    }

    // ------------------------------------------------------------------ helpers

    private Deposit openDeposit(String amount) {
        return deposits.save(newDeposit(amount));
    }

    private Deposit newDeposit(String amount) {
        return Deposit.pending(UUID.randomUUID().toString(), "user-" + UUID.randomUUID(), newAccount(),
                new BigDecimal(amount), "NGN", Instant.now().plus(Duration.ofHours(24)));
    }

    private String outcome(String type, String eventId, String depositId, String received, String currency, String reason)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stripeEventId", eventId);
        payload.put("paymentIntentId", "pi_" + UUID.randomUUID());
        payload.put("depositId", depositId);
        payload.put("amountReceived", received);
        payload.put("currency", currency);
        payload.put("providerReceipt", "{\"id\":\"" + eventId + "\",\"currency\":\""
                + currency.toLowerCase() + "\",\"amount_received\":\"" + received + "\"}");
        if (reason != null) {
            payload.put("failureReason", reason);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        return mapper.writeValueAsString(envelope);
    }

    private String statusOf(String depositId) {
        return jdbc.queryForObject("SELECT status FROM deposit WHERE id = ?", String.class, depositId);
    }

    private int processedEvents(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = 'wallet-deposit' AND event_id = ?",
                Integer.class, eventId);
    }

    private int outboxRows(String depositId, String topic) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND topic = ?",
                Integer.class, depositId, topic);
    }

    private int auditRows(String depositId, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'deposit' AND entity_id = ? AND action = ?",
                Integer.class, depositId, action);
    }

    private static String newEventId() {
        return "evt_" + UUID.randomUUID();
    }

    private static String newAccount() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }
}
