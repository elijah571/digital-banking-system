package org.elibanking.transactionservice.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.config.OtpTopicConfig;
import org.elibanking.transactionservice.client.AccountInfo;
import org.elibanking.transactionservice.client.AccountService;
import org.elibanking.transactionservice.client.LedgerClient;
import org.elibanking.transactionservice.client.LedgerClient.LedgerEntryRequest;
import org.elibanking.transactionservice.entity.Transaction;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.entity.TransactionType;
import org.elibanking.transactionservice.dto.TransactionResponse;
import org.elibanking.transactionservice.exception.InvalidOtpException;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.security.UserTokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transfer OTPs (wallet-plan D6, slice 3c.5): the code is hashed in Redis and sent once, on {@code notification.otp}; a wrong
 * code is counted; the third one flags, refunds and blocks. The Kafka template is mocked so every send can be read back.
 */
@SpringBootTest(properties = {"transfer.pin-required=false", "internal-auth.jwt-secret=transaction-service-test-secret-0123456789", "otp.hmac-key=transaction-service-test-otp-hmac-key-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET, "outbox.relay.enabled=false", "transfer.recovery.enabled=false"})
@Import(TestcontainersConfiguration.class)
class OtpTest {

    private static final String LOGGER = "org.elibanking.transactionservice";

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

    @Autowired
    ObjectMapper mapper;

    @Autowired
    OtpCodes otps;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    LedgerClient ledger;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(LOGGER)).addAppender(logs);
    }

    @AfterEach
    void stopCapturingLogs() {
        ((Logger) LoggerFactory.getLogger(LOGGER)).detachAppender(logs);
        logs.stop();
    }

    @Test
    void theCodeIsKeptHashedAndSentOnceOnTheOtpTopic() {
        String id = processingTransfer();

        String otp = generateCode(id);

        assertTrue(otp.matches("\\d{6}"), "a six-digit code");
        String stored = redis.opsForValue().get(OtpCodes.codeKey(id));
        assertNotEquals(otp, stored, "Redis does not hold the code itself");
        assertEquals(otps.hash(id, otp), stored, "Redis holds the keyed hash of the code");
        assertEquals("0", redis.opsForValue().get(OtpCodes.attemptsKey(id)), "no attempts yet");
        assertEquals(TransactionStatus.PENDING_VERIFICATION, transactions.findById(id).orElseThrow().getStatus());
    }

    @Test
    void twoWrongCodesWaitAndTheThirdFlagsRefundsAndBlocks() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer(sender, receiver);
        String otp = generateCode(id);
        String wrong = otp.equals("000000") ? "000001" : "000000";

        InvalidOtpException first = assertThrows(InvalidOtpException.class,
                () -> transfers.verifyOTP(id, wrong, new Caller(null, null, null)));
        assertEquals("Incorrect OTP. 2 attempts left", first.getMessage());
        assertEquals(TransactionStatus.PENDING_VERIFICATION, transactions.findById(id).orElseThrow().getStatus(),
                "the first wrong code leaves the transfer waiting");

        InvalidOtpException second = assertThrows(InvalidOtpException.class,
                () -> transfers.verifyOTP(id, wrong, new Caller(null, null, null)));
        assertEquals("Incorrect OTP. 1 attempt left", second.getMessage());
        assertEquals(TransactionStatus.PENDING_VERIFICATION, transactions.findById(id).orElseThrow().getStatus(),
                "the second wrong code leaves the transfer waiting");

        transfers.verifyOTP(id, wrong, new Caller(null, null, null));

        assertEquals(TransactionStatus.FLAGGED, transactions.findById(id).orElseThrow().getStatus());
        assertNull(redis.opsForValue().get(OtpCodes.codeKey(id)), "the code is gone after the third wrong one");
        assertEquals(1, outboxCount("fraud.detected", id));
        ArgumentCaptor<LedgerEntryRequest> posted = ArgumentCaptor.forClass(LedgerEntryRequest.class);
        verify(ledger, times(1)).post(posted.capture());
        assertEquals("tx-" + id + "-release", posted.getValue().idempotencyKey(), "the refund is a release");
    }

    @Test
    void aCorrectCodeAfterTwoWrongOnesStillCompletes() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer(sender, receiver);
        String otp = generateCode(id);
        String wrong = otp.equals("000000") ? "000001" : "000000";
        assertThrows(InvalidOtpException.class, () -> transfers.verifyOTP(id, wrong, new Caller(null, null, null)));
        assertThrows(InvalidOtpException.class, () -> transfers.verifyOTP(id, wrong, new Caller(null, null, null)));

        transfers.verifyOTP(id, otp, new Caller(null, null, null));

        assertEquals(TransactionStatus.COMPLETED, transactions.findById(id).orElseThrow().getStatus());
        assertNull(redis.opsForValue().get(OtpCodes.codeKey(id)), "a code is single use");
    }

    @Test
    void theCodeAppearsOnlyOnTheOtpTopicAndNeverInALog() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer(sender, receiver);
        String otp = generateCode(id);
        String wrong = otp.equals("000000") ? "000001" : "000000";
        assertThrows(InvalidOtpException.class, () -> transfers.verifyOTP(id, wrong, new Caller(null, null, null)));
        transfers.verifyOTP(id, otp, new Caller(null, null, null));

        List<String> sent = outboxTopics(id);
        assertTrue(sent.contains(OtpTopicConfig.NOTIFICATION_OTP_TOPIC), "the code is sent on notification.otp");
        for (int i = 0; i < sent.size(); i++) {
            if (!sent.get(i).equals(OtpTopicConfig.NOTIFICATION_OTP_TOPIC)) {
                assertFalse(outboxPayloads(id).get(i).contains(otp),
                        "no code in " + sent.get(i));
            }
        }
        assertEquals(0, logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains(otp)).count(),
                "no log line carries the code");
    }

    @Test
    void aCodeFromOneTransferDoesNotOpenAnother() {
        assertNotEquals(otps.hash("tx-a", "123456"), otps.hash("tx-b", "123456"));
        assertFalse(otps.matches("tx-b", "123456", otps.hash("tx-a", "123456")));
        assertFalse(otps.matches("tx-a", null, otps.hash("tx-a", "123456")));
    }

    /** The service does not start without a key of at least 32 bytes, and the error never shows the key. */
    @Test
    void theHmacKeyIsRequiredAndAtLeastThirtyTwoBytes() {
        assertThrows(IllegalStateException.class, () -> new OtpCodes(""));
        assertThrows(IllegalStateException.class, () -> new OtpCodes(null));
        IllegalStateException short31 = assertThrows(IllegalStateException.class, () -> new OtpCodes("too-short-key-value"));
        assertFalse(short31.getMessage().contains("too-short-key-value"), "the message never shows the key");
        assertDoesNotThrow(() -> new OtpCodes("x".repeat(OtpCodes.MIN_KEY_BYTES)));
    }

    /**
     * Ten wrong codes at once on one transfer (wallet-plan 3c.5 review, item 2). Exactly three are counted: two get a 400, the
     * third flags, refunds and blocks once. The other seven are refused and do not block or refund again.
     */
    @Test
    void tenParallelWrongCodesCountThreeAndBlockOnce() throws Exception {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer(sender, receiver);
        String otp = generateCode(id);
        String wrong = otp.equals("000000") ? "000001" : "000000";

        List<Object> outcomes = Concurrently.sameCall(10, () -> transfers.verifyOTP(id, wrong, new Caller(null, null, null)));

        assertEquals(2, Concurrently.count(outcomes, InvalidOtpException.class), "two wrong codes get a 400 and wait");
        assertEquals(1, Concurrently.count(outcomes, TransactionResponse.class), "the third wrong code flags the transfer");
        assertEquals(7, Concurrently.count(outcomes, IllegalStateException.class), "the rest are refused, not counted again");
        assertEquals(TransactionStatus.FLAGGED, transactions.findById(id).orElseThrow().getStatus());
        assertEquals(1, outboxCount("fraud.detected", id));
        verify(ledger, times(1)).post(any());
    }

    /**
     * The same correct code sent twice at once (wallet-plan 3c.5 review, item 3). One succeeds and the transfer settles once;
     * the other is refused.
     */
    @Test
    void theSameCorrectCodeSentTwiceAtOnceSettlesOnce() throws Exception {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = processingTransfer(sender, receiver);
        String otp = generateCode(id);

        List<Object> outcomes = Concurrently.sameCall(2, () -> transfers.verifyOTP(id, otp, new Caller(null, null, null)));

        assertEquals(1, Concurrently.count(outcomes, TransactionResponse.class), "one of the two succeeds");
        assertEquals(1, Concurrently.count(outcomes, IllegalStateException.class), "the other is refused");
        assertEquals(TransactionStatus.COMPLETED, transactions.findById(id).orElseThrow().getStatus());
        verify(ledger, times(1)).post(any());
        assertNull(redis.opsForValue().get(OtpCodes.codeKey(id)), "a code is single use");
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the generation the way the fraud result does, and reads the code back from the OTP event. */
    private String generateCode(String id) {
        consumer.consumeVerificationRequired(Map.of("transactionId", id, "accountNumber", "1000000001",
                "reason", "large amount", "amount", "250.00"));
        JsonNode event = latestOutboxEvent(OtpTopicConfig.NOTIFICATION_OTP_TOPIC, id);
        return event.path("payload").path("otp").asString();
    }

    private long outboxCount(String topic, String aggregateId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE topic = ? AND aggregate_id = ?",
                Long.class, topic, aggregateId);
    }

    private List<String> outboxTopics(String aggregateId) {
        return jdbc.query("SELECT topic FROM outbox WHERE aggregate_id = ? ORDER BY id",
                (rs, row) -> rs.getString("topic"), aggregateId);
    }

    private List<String> outboxPayloads(String aggregateId) {
        return jdbc.query("SELECT payload FROM outbox WHERE aggregate_id = ? ORDER BY id",
                (rs, row) -> rs.getString("payload"), aggregateId);
    }

    private JsonNode latestOutboxEvent(String topic, String aggregateId) {
        String payload = jdbc.queryForObject("SELECT payload FROM outbox WHERE topic = ? AND aggregate_id = ? ORDER BY id DESC LIMIT 1",
                String.class, topic, aggregateId);
        return mapper.readTree(payload);
    }

    private String processingTransfer() {
        return processingTransfer(randomAccount(), randomAccount());
    }

    private String processingTransfer(String sender, String receiver) {
        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(sender);
        transaction.setSenderUserId("owner-" + sender);
        transaction.setReceiverAccountNumber(receiver);
        transaction.setReferenceNumber("REF-OTP-" + UUID.randomUUID());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setAmount(new BigDecimal("250.00"));
        return transactions.saveAndFlush(transaction).getId();
    }

    private void stubAccounts(String sender, String receiver) {
        when(accountService.getAccount(sender)).thenReturn(new AccountInfo(sender, "owner-" + sender, "ACTIVE"));
        when(accountService.getAccount(receiver)).thenReturn(new AccountInfo(receiver, "owner-" + receiver, "ACTIVE"));
    }

    private static String randomAccount() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }
}
