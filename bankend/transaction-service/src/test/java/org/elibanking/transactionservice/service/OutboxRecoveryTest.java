package org.elibanking.transactionservice.service;

import feign.FeignException;
import feign.Request;
import feign.Response;
import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.client.AccountInfo;
import org.elibanking.transactionservice.client.AccountService;
import org.elibanking.transactionservice.client.LedgerClient;
import org.elibanking.transactionservice.client.LedgerClient.LedgerEntryRequest;
import org.elibanking.transactionservice.dto.TransactionRequest;
import org.elibanking.transactionservice.entity.Transaction;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.entity.TransactionType;
import org.elibanking.transactionservice.outbox.OutboxRelay;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The outbox and the hold (wallet-plan 3.8, slice 3c.4): the row and its event commit before the hold, the relay publishes the event
 * only once the transfer is held, a crash before the hold is completed by recovery exactly once, and a refused hold is never published.
 * The scheduled jobs are off in this context; the test calls the relay and the recovery itself.
 */
@SpringBootTest(properties = {"transfer.pin-required=false", "internal-auth.jwt-secret=transaction-service-test-secret-0123456789", "otp.hmac-key=transaction-service-test-otp-hmac-key-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET, "outbox.relay.enabled=false", "transfer.recovery.enabled=false"})
@Import(TestcontainersConfiguration.class)
class OutboxRecoveryTest {

    @Autowired
    TransactionalService transfers;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    OutboxRelay relay;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    LedgerClient ledger;

    @Test
    void theRowAndItsEventAreCommittedBeforeTheHoldAndPublishedAfterIt() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver, "ACTIVE");
        AtomicInteger outboxRowsAtTheHold = new AtomicInteger(-1);
        when(ledger.post(any())).thenAnswer(invocation -> {
            LedgerEntryRequest request = invocation.getArgument(0);
            outboxRowsAtTheHold.set(count("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?", request.correlationId()));
            return new LedgerClient.LedgerEntryResponse(1L, false);
        });

        String id = transfers.transfer(request(sender, receiver, "100.00"), new Caller(null, null, null)).getId();

        assertEquals(1, outboxRowsAtTheHold.get(), "the row and its event were committed before the hold");
        assertEquals(0L, sent(id), "not published by the time the transfer returns");
        relay.relay();
        assertEquals(1L, sent(id), "published by the relay once the transfer is held");
    }

    @Test
    void aCrashBeforeTheHoldIsCompletedByRecoveryExactlyOnce() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver, "ACTIVE");
        when(ledger.post(any())).thenThrow(new IllegalStateException("the process died before the hold came back"));
        assertThrows(IllegalStateException.class,
                () -> transfers.transfer(request(sender, receiver, "100.00"), new Caller(null, null, null)));
        String id = transactions.findBySenderAccountNumberOrderByCreatedAtDesc(sender).get(0).getId();
        jdbc.update("UPDATE transactions SET created_at = created_at - INTERVAL 10 MINUTE WHERE id = ?", id);
        // doReturn: a when(...) here would call the mock, and the earlier stub would throw during the setup.
        doReturn(new LedgerClient.LedgerEntryResponse(1L, false)).when(ledger).post(any());

        transfers.holdStuckTransfers();

        Transaction row = transactions.findById(id).orElseThrow();
        assertNotNull(row.getHeldAt(), "recovery held the transfer");
        assertEquals(TransactionStatus.PROCESSING, row.getStatus());
        verify(ledger, times(2)).post(any());   // the attempt that crashed, and the recovery

        transfers.holdStuckTransfers();
        verify(ledger, times(2)).post(any());   // nothing is left to hold, so no second hold
    }

    @Test
    void aRefusedHoldFailsTheRowAndTheRelayNeverPublishesIt() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver, "ACTIVE");
        when(ledger.post(any())).thenThrow(FeignException.errorStatus("LedgerClient#post",
                failure(422, "{\"detail\":\"Insufficient funds for account " + sender + "\"}")));
        assertThrows(FeignException.class,
                () -> transfers.transfer(request(sender, receiver, "100.00"), new Caller(null, null, null)));
        String id = transactions.findBySenderAccountNumberOrderByCreatedAtDesc(sender).get(0).getId();

        Transaction row = transactions.findById(id).orElseThrow();
        assertEquals(TransactionStatus.FAILED, row.getStatus());
        assertNull(row.getHeldAt(), "a refused transfer is never held");
        relay.relay();
        assertEquals(0L, sent(id), "a transfer that was not held is never published");
    }

    // ------------------------------------------------------------------ helpers

    private int count(String sql, String argument) {
        Long n = jdbc.queryForObject(sql, Long.class, argument);
        return n == null ? -1 : n.intValue();
    }

    private long sent(String transactionId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND sent_at IS NOT NULL",
                Long.class, transactionId);
        return n == null ? -1 : n;
    }

    private static String randomAccount() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }

    private void stubAccounts(String sender, String receiver, String senderStatus) {
        when(accountService.getAccount(sender)).thenReturn(new AccountInfo(sender, "owner-" + sender, senderStatus));
        when(accountService.getAccount(receiver)).thenReturn(new AccountInfo(receiver, "owner-" + receiver, "ACTIVE"));
    }

    private static TransactionRequest request(String sender, String receiver, String amount) {
        TransactionRequest request = new TransactionRequest();
        request.setSenderAccountNumber(sender);
        request.setReceiverAccountNumber(receiver);
        request.setAmount(new BigDecimal(amount));
        request.setType(TransactionType.TRANSFER);
        request.setDescription("outbox test");
        return request;
    }

    private static Response failure(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "http://account-service/internal/v1/ledger/entries",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().status(status).reason("refused").request(request).headers(Map.of())
                .body(body, StandardCharsets.UTF_8).build();
    }
}
