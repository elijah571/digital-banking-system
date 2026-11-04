package org.elibanking.transactionservice.service;

import feign.FeignException;
import feign.Request;
import feign.Response;
import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.client.AccountInfo;
import org.elibanking.transactionservice.client.AccountService;
import org.elibanking.transactionservice.client.LedgerClient;
import org.elibanking.transactionservice.client.LedgerClient.LedgerEntryRequest;
import org.elibanking.transactionservice.client.LedgerClient.LedgerLine;
import org.elibanking.transactionservice.dto.TransactionRequest;
import org.elibanking.transactionservice.entity.Transaction;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.entity.TransactionType;
import org.elibanking.transactionservice.exception.TransactionRejectedException;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transfer saga on the ledger (wallet-plan 3.4, slice 3c.3): hold, settle and release, with the keys from section 3.4. The
 * account-service ledger is mocked here; its own behaviour (locks, idempotency, funds) is tested in account-service.
 */
@SpringBootTest(properties = {"internal-auth.jwt-secret=transaction-service-test-secret-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET})
@Import(TestcontainersConfiguration.class)
class TransferLedgerTest {

    @Autowired
    TransactionalService transfers;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    RedisTemplate<String, String> redis;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    LedgerClient ledger;

    @Test
    void aTransferHoldsTheSenderAndSettlesToTheReceiver() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver, "ACTIVE");
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));

        String id = transfers.transfer(request(sender, receiver, "100.00"), new Caller(null, null, null)).getId();

        ArgumentCaptor<LedgerEntryRequest> posted = ArgumentCaptor.forClass(LedgerEntryRequest.class);
        verify(ledger).post(posted.capture());
        LedgerEntryRequest hold = posted.getValue();
        assertEquals("tx-" + id + "-hold", hold.idempotencyKey());
        assertLine(hold.lines().get(0), sender, null, "DEBIT", "100.00");
        assertLine(hold.lines().get(1), null, "TRANSFER_IN_FLIGHT:NGN", "CREDIT", "100.00");

        transfers.processCleanResult(id);

        // A fresh captor: the first one already holds the hold, and a reused captor would collect it again.
        ArgumentCaptor<LedgerEntryRequest> both = ArgumentCaptor.forClass(LedgerEntryRequest.class);
        verify(ledger, times(2)).post(both.capture());
        LedgerEntryRequest settle = both.getAllValues().get(1);
        assertEquals("tx-" + id + "-settle", settle.idempotencyKey());
        assertLine(settle.lines().get(0), null, "TRANSFER_IN_FLIGHT:NGN", "DEBIT", "100.00");
        assertLine(settle.lines().get(1), receiver, null, "CREDIT", "100.00");
        assertEquals(TransactionStatus.COMPLETED, transactions.findById(id).orElseThrow().getStatus());
    }

    @Test
    void anInsufficientBalanceFailsTheTransferAndTheRowSaysWhy() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver, "ACTIVE");
        when(ledger.post(any())).thenThrow(FeignException.errorStatus("LedgerClient#post",
                failure(422, "{\"detail\":\"Insufficient funds for account " + sender + "\"}")));

        assertThrows(FeignException.class, () -> transfers.transfer(request(sender, receiver, "100.00"), new Caller(null, null, null)));

        Transaction row = transactions.findBySenderAccountNumberOrderByCreatedAtDesc(sender).get(0);
        assertEquals(TransactionStatus.FAILED, row.getStatus());
        assertTrue(row.getFailureReason().contains("422"), row.getFailureReason());
        verify(ledger, times(1)).post(any());
    }

    @Test
    void aBlockedAccountIsRejectedBeforeAnyMoneyMoves() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver, "BLOCKED");

        assertThrows(TransactionRejectedException.class,
                () -> transfers.transfer(request(sender, receiver, "100.00"), new Caller(null, null, null)));

        verify(ledger, never()).post(any());
        assertTrue(transactions.findBySenderAccountNumberOrderByCreatedAtDesc(sender).isEmpty(),
                "no row is written for a refused transfer");
    }

    @Test
    void aCompletedTransferIsNotSettledAgain() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver, "ACTIVE");
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = transfers.transfer(request(sender, receiver, "100.00"), new Caller(null, null, null)).getId();

        transfers.processCleanResult(id);
        transfers.processCleanResult(id);

        verify(ledger, times(2)).post(any());   // the hold, and one settle
    }

    @Test
    void aWrongOtpReleasesTheHoldBackToTheSenderWithItsOwnKey() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver, "ACTIVE");
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String id = transfers.transfer(request(sender, receiver, "250.00"), new Caller(null, null, null)).getId();
        Transaction row = transactions.findById(id).orElseThrow();
        row.setStatus(TransactionStatus.PENDING_VERIFICATION);
        transactions.saveAndFlush(row);
        redis.opsForValue().set("verification:otp:" + id, "123456");

        transfers.verifyOTP(id, "000000", new Caller(null, null, null));

        ArgumentCaptor<LedgerEntryRequest> posted = ArgumentCaptor.forClass(LedgerEntryRequest.class);
        verify(ledger, times(2)).post(posted.capture());
        LedgerEntryRequest release = posted.getAllValues().get(1);
        assertEquals("tx-" + id + "-release", release.idempotencyKey());
        assertLine(release.lines().get(0), null, "TRANSFER_IN_FLIGHT:NGN", "DEBIT", "250.00");
        assertLine(release.lines().get(1), sender, null, "CREDIT", "250.00");
        assertEquals(TransactionStatus.FLAGGED, transactions.findById(id).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------ helpers

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
        request.setDescription("ledger test");
        return request;
    }

    private static void assertLine(LedgerLine line, String account, String system, String side, String amount) {
        assertEquals(account, line.accountNumber(), "account");
        assertEquals(system, line.systemAccount(), "system account");
        assertEquals(side, line.side(), "side");
        assertEquals(amount, line.amount(), "amount");
        assertEquals("NGN", line.currency(), "currency");
    }

    private static Response failure(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "http://account-service/internal/v1/ledger/entries",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().status(status).reason("refused").request(request).headers(Map.of())
                .body(body, StandardCharsets.UTF_8).build();
    }
}
