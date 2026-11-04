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
import org.elibanking.transactionservice.exception.InvalidOtpException;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.security.UserTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transfer OTPs (wallet-plan D6, slice 3c.5): the code is hashed in Redis and sent once, on {@code notification.otp}; a wrong
 * code is counted; the third one flags, refunds and blocks. The Kafka template is mocked so every send can be read back.
 */
@SpringBootTest(properties = {"internal-auth.jwt-secret=transaction-service-test-secret-0123456789",
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
    OtpCodes otps;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    LedgerClient ledger;

    @MockitoBean
    KafkaTemplate<String, Object> kafka;

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
        verify(kafka).send(eq("fraud.detected"), eq(id), any());
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

        ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(kafka, atLeastOnce()).send(topics.capture(), anyString(), values.capture());
        List<String> sent = topics.getAllValues();
        assertTrue(sent.contains(OtpTopicConfig.NOTIFICATION_OTP_TOPIC), "the code is sent on notification.otp");
        for (int i = 0; i < sent.size(); i++) {
            if (!sent.get(i).equals(OtpTopicConfig.NOTIFICATION_OTP_TOPIC)) {
                assertFalse(String.valueOf(values.getAllValues().get(i)).contains(otp),
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

    // ------------------------------------------------------------------ helpers

    /** Runs the generation the way the fraud result does, and reads the code back from the OTP event. */
    private String generateCode(String id) {
        consumer.consumeVerificationRequired(Map.of("transactionId", id, "accountNumber", "1000000001",
                "reason", "large amount", "amount", "250.00"));
        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(topic.capture(), eq(id), event.capture());
        assertEquals(OtpTopicConfig.NOTIFICATION_OTP_TOPIC, topic.getValue());
        return String.valueOf(((Map<?, ?>) event.getValue()).get("otp"));
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
