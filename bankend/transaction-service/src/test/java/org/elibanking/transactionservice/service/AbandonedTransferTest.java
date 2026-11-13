package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.client.AccountService;
import org.elibanking.transactionservice.client.LedgerClient;
import org.elibanking.transactionservice.dto.TransactionResponse;
import org.elibanking.transactionservice.entity.Transaction;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.entity.TransactionType;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.security.UserTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A transfer left waiting for its code (wallet-plan 8.34). The recovery job releases it exactly once and ends it as EXPIRED.
 * A claim that a crash left half done is finished from its recorded outcome, never released. A code that arrives after the
 * release is refused.
 */
@SpringBootTest(properties = {"transfer.pin-required=false", "internal-auth.jwt-secret=transaction-service-test-secret-0123456789",
        "otp.hmac-key=transaction-service-test-otp-hmac-key-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET, "outbox.relay.enabled=false", "transfer.recovery.enabled=false"})
@Import(TestcontainersConfiguration.class)
class AbandonedTransferTest {

    private static final Caller CALLER = new Caller(null, null, null);

    @Autowired
    TransactionalService transfers;

    @Autowired
    TransactionEventConsumer consumer;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    RedisTemplate<String, String> redis;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    LedgerClient ledger;

    @Autowired
    ObjectMapper mapper;

    /** Other tests leave transfers waiting for a code. None of them may be swept up by these tests. */
    @BeforeEach
    void endEveryWaitingTransferLeftByAnEarlierTest() {
        jdbc.update("UPDATE transactions SET status = 'FAILED' WHERE status = 'PENDING_VERIFICATION'");
    }

    @Test
    void aTransferLeftWaitingIsReleasedOnceAndALateCodeIsRefused() throws Exception {
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer();
        String otp = generateCode(id);
        leaveItAbandoned(id);
        forgetCode(id);

        // Two recovery runs at once, and a third one after them: the release is posted once.
        Concurrently.together(() -> {
            transfers.expireAbandonedVerifications();
            return null;
        }, () -> {
            transfers.expireAbandonedVerifications();
            return null;
        });
        transfers.expireAbandonedVerifications();

        assertEquals(TransactionStatus.EXPIRED, statusOf(id));
        ArgumentCaptor<LedgerClient.LedgerEntryRequest> posted = ArgumentCaptor.forClass(LedgerClient.LedgerEntryRequest.class);
        verify(ledger, times(1)).post(posted.capture());
        assertEquals("tx-" + id + "-release", posted.getValue().idempotencyKey());

        // The code arrives late: it is refused, and nothing is released again.
        assertThrows(IllegalStateException.class, () -> transfers.verifyOTP(id, otp, CALLER));
        verify(ledger, times(1)).post(any());
        assertEquals(TransactionStatus.EXPIRED, statusOf(id));
    }

    @Test
    void aCorrectCodeClaimedBeforeACrashIsSettledNotReleased() throws Exception {
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer();
        String otp = generateCode(id);
        leaveItAbandoned(id);
        forgetCode(id);
        // The claim was made, and the process died before the settle and the save.
        claimLeftBehind(id, OtpCodes.Outcome.COMPLETE, Duration.ofMinutes(5));

        transfers.expireAbandonedVerifications();

        assertEquals(TransactionStatus.COMPLETED, statusOf(id));
        ArgumentCaptor<LedgerClient.LedgerEntryRequest> posted = ArgumentCaptor.forClass(LedgerClient.LedgerEntryRequest.class);
        verify(ledger, times(1)).post(posted.capture());
        assertEquals("tx-" + id + "-settle", posted.getValue().idempotencyKey());
        assertThrows(IllegalStateException.class, () -> transfers.verifyOTP(id, otp, CALLER));
        verify(ledger, times(1)).post(any());
    }

    @Test
    void aBlockClaimedBeforeACrashIsFinishedWithOneRelease() {
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer();
        generateCode(id);
        leaveItAbandoned(id);
        forgetCode(id);
        claimLeftBehind(id, OtpCodes.Outcome.FLAG, Duration.ofMinutes(5));

        transfers.expireAbandonedVerifications();
        transfers.expireAbandonedVerifications();

        assertEquals(TransactionStatus.FLAGGED, statusOf(id));
        assertEquals(1, outboxCount("fraud.detected", id));
        verify(ledger, times(1)).post(any());
    }

    @Test
    void anExpiryClaimedBeforeACrashIsFinishedWithOneRelease() {
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer();
        generateCode(id);
        leaveItAbandoned(id);
        forgetCode(id);
        claimLeftBehind(id, OtpCodes.Outcome.EXPIRE, Duration.ofMinutes(5));

        transfers.expireAbandonedVerifications();
        transfers.expireAbandonedVerifications();

        assertEquals(TransactionStatus.EXPIRED, statusOf(id));
        verify(ledger, times(1)).post(any());
    }

    @Test
    void aClaimThatIsStillBeingActedOnIsLeftAlone() {
        String id = processingTransfer();
        generateCode(id);
        leaveItAbandoned(id);
        forgetCode(id);
        claimLeftBehind(id, OtpCodes.Outcome.COMPLETE, Duration.ZERO);

        transfers.expireAbandonedVerifications();

        assertEquals(TransactionStatus.PENDING_VERIFICATION, statusOf(id));
        verify(ledger, never()).post(any());
    }

    @Test
    void aRecentTransferAndATransferWithAValidCodeAreLeftAlone() {
        String recent = processingTransfer();
        generateCode(recent);
        forgetCode(recent);
        String stillValid = processingTransfer();
        generateCode(stillValid);
        leaveItAbandoned(stillValid);

        transfers.expireAbandonedVerifications();

        assertEquals(TransactionStatus.PENDING_VERIFICATION, statusOf(recent), "too new to be abandoned");
        assertEquals(TransactionStatus.PENDING_VERIFICATION, statusOf(stillValid), "its code can still be entered");
        verify(ledger, never()).post(any());
    }

    @Test
    void aRecoveryRunRacingALateCodeReleasesOnce() throws Exception {
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer();
        String otp = generateCode(id);
        leaveItAbandoned(id);
        forgetCode(id);

        List<Object> outcomes = Concurrently.together(() -> {
            transfers.expireAbandonedVerifications();
            return null;
        }, () -> transfers.verifyOTP(id, otp, CALLER));

        assertEquals(TransactionStatus.EXPIRED, statusOf(id));
        verify(ledger, times(1)).post(any());
        assertEquals(1, Concurrently.count(outcomes, TransactionResponse.class)
                + Concurrently.count(outcomes, IllegalStateException.class), "the late code got one answer");
    }

    // ------------------------------------------------------------------ helpers

    private String processingTransfer() {
        String sender = randomAccount();
        String receiver = randomAccount();
        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(sender);
        transaction.setSenderUserId("owner-" + sender);
        transaction.setReceiverAccountNumber(receiver);
        transaction.setReferenceNumber("REF-ABANDON-" + UUID.randomUUID());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setAmount(new BigDecimal("250.00"));
        return transactions.saveAndFlush(transaction).getId();
    }

    /** Runs the generation the way the fraud result does, and reads the code back from the OTP event. */
    private String generateCode(String id) {
        consumer.consumeVerificationRequired(Map.of("transactionId", id, "accountNumber", "1000000001",
                "reason", "large amount", "amount", "250.00"));
        JsonNode event = latestOutboxEvent("notification.otp", id);
        return event.path("payload").path("otp").asString();
    }

    private long outboxCount(String topic, String aggregateId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE topic = ? AND aggregate_id = ?",
                Long.class, topic, aggregateId);
    }

    private JsonNode latestOutboxEvent(String topic, String aggregateId) {
        String payload = jdbc.queryForObject("SELECT payload FROM outbox WHERE topic = ? AND aggregate_id = ? ORDER BY id DESC LIMIT 1",
                String.class, topic, aggregateId);
        return mapper.readTree(payload);
    }

    /** Puts the transfer's creation far enough back that it is past the code's lifetime and the margin. */
    private void leaveItAbandoned(String id) {
        jdbc.update("UPDATE transactions SET created_at = ? WHERE id = ?", LocalDateTime.now().minusHours(1), id);
    }

    /** The code's five minutes are over. */
    private void forgetCode(String id) {
        redis.delete(OtpCodes.codeKey(id));
    }

    /** A claim stored as a crash left it: decided, and made {@code age} ago. */
    private void claimLeftBehind(String id, OtpCodes.Outcome outcome, Duration age) {
        redis.opsForValue().set(OtpCodes.decisionKey(id), OtpCodes.encodeDecision(outcome, Instant.now().minus(age)));
    }

    private TransactionStatus statusOf(String id) {
        return transactions.findById(id).orElseThrow().getStatus();
    }

    private static String randomAccount() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }
}
