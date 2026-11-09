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
import org.elibanking.transactionservice.dto.TransactionResponse;
import org.elibanking.transactionservice.entity.TransactionType;
import org.elibanking.transactionservice.exception.IdempotencyInProgressException;
import org.elibanking.transactionservice.exception.IdempotencyKeyMismatchException;
import org.elibanking.transactionservice.exception.TransactionRejectedException;
import org.elibanking.transactionservice.idempotency.IdempotencyService;
import org.elibanking.transactionservice.repository.TransactionRepository;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /api/v1/transactions with an Idempotency-Key (wallet-plan 3.6). The ledger is mocked; its own idempotency is tested in
 * account-service. The money-safety case is the crash after the transfer was saved: the retry resumes that transfer.
 */
@SpringBootTest(properties = {"transfer.pin-required=false", "internal-auth.jwt-secret=transaction-service-test-secret-0123456789", "otp.hmac-key=transaction-service-test-otp-hmac-key-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET, "outbox.relay.enabled=false", "transfer.recovery.enabled=false", "transfer.own-bank-code=999"})
@Import(TestcontainersConfiguration.class)
class IdempotencyTest {

    private static final Caller INTERNAL = new Caller(null, null, null);
    private static final String SCOPE = "internal " + IdempotentTransfers.ROUTE;

    @Autowired
    IdempotentTransfers transfers;

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    LedgerClient ledger;

    @MockitoBean
    WalletTransferClient wallet;

    @Test
    void aRepeatWithTheSameKeyAndBodyReturnsTheFirstResultAndMovesMoneyOnce() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        TransactionRequest request = request(sender, receiver, "100.00");
        String key = UUID.randomUUID().toString();

        TransactionResponse first = transfers.transfer(key, request, INTERNAL);
        TransactionResponse second = transfers.transfer(key, request, INTERNAL);

        assertEquals(first.getId(), second.getId(), "the repeat is the same transfer");
        assertEquals(1, transactions.findBySenderAccountNumberOrderByCreatedAtDesc(sender).size(), "one transfer exists");
        verify(ledger, times(1)).post(any());
    }

    @Test
    void anExternalTransferValidatesItsReferenceAndReplaysWalletAcceptance() {
        String sender = randomAccount();
        String receiver = randomAccount();
        String userId = "owner-" + sender;
        TransactionRequest request = request(sender, receiver, "100.00");
        request.setBankCode("058");
        request.setNameEnquiryRef("opaque-reference");
        Caller caller = new Caller(userId, "owner@example.test", "USER");
        String key = UUID.randomUUID().toString();
        when(wallet.validate(userId, "opaque-reference", "058", receiver))
                .thenReturn(new WalletTransferClient.Name("Destination Example"));
        when(wallet.transfer(eq(key), eq(userId), eq(sender), eq(receiver), eq("Destination Example"), eq("100.00"),
                eq("058"), eq("opaque-reference"), nullable(String.class), nullable(String.class)))
                .thenReturn(new WalletTransferClient.Reply(202, "{\"payoutId\":7,\"status\":\"APPROVED\"}"));

        IdempotentTransfers.ExternalReply first = transfers.externalTransfer(key, request, caller);
        IdempotentTransfers.ExternalReply replay = transfers.externalTransfer(key, request, caller);

        assertEquals(202, first.status());
        assertEquals(first.status(), replay.status());
        assertEquals(json.readTree(first.body()), json.readTree(replay.body()));
        verify(wallet, times(1)).validate(userId, "opaque-reference", "058", receiver);
        verify(wallet, times(1)).transfer(eq(key), eq(userId), eq(sender), eq(receiver), eq("Destination Example"),
                eq("100.00"), eq("058"), eq("opaque-reference"), nullable(String.class), nullable(String.class));
        assertTrue(transfers.isExternalBank("058"));
        assertFalse(transfers.isExternalBank("999"));
    }

    @Test
    void aRepeatWithTheSameKeyAndADifferentBodyIsRefusedAndMovesNothing() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        String key = UUID.randomUUID().toString();
        transfers.transfer(key, request(sender, receiver, "100.00"), INTERNAL);

        assertThrows(IdempotencyKeyMismatchException.class,
                () -> transfers.transfer(key, request(sender, receiver, "101.00"), INTERNAL));
        verify(ledger, times(1)).post(any());
    }

    @Test
    void aRepeatWhileTheFirstAttemptIsStillRunningIsRefused() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        TransactionRequest request = request(sender, receiver, "100.00");
        String key = UUID.randomUUID().toString();
        // Another attempt holds the key with a live lease.
        idempotency.begin(SCOPE, key, IdempotencyService.hash(IdempotentTransfers.canonical(request)), Duration.ofMinutes(5));

        assertThrows(IdempotencyInProgressException.class, () -> transfers.transfer(key, request, INTERNAL));
        verify(ledger, times(0)).post(any());
    }

    @Test
    void aRetryAfterACrashResumesTheSameTransferAndMakesNoSecondOne() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any()))
                .thenThrow(FeignException.errorStatus("LedgerClient#post", failure(503, "{}")))
                .thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));
        TransactionRequest request = request(sender, receiver, "100.00");
        String key = UUID.randomUUID().toString();

        // The first attempt saves the transfer, and then the ledger is down.
        assertThrows(FeignException.class, () -> transfers.transfer(key, request, INTERNAL));
        String savedId = jdbc.queryForObject("SELECT transaction_id FROM idempotency_record WHERE idem_key = ?", String.class, key);
        assertEquals(1, transactions.findBySenderAccountNumberOrderByCreatedAtDesc(sender).size());

        // The first attempt is gone (its lease ends), and the retry takes over.
        jdbc.update("UPDATE idempotency_record SET lease_until = CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE idem_key = ?", key);
        TransactionResponse resumed = transfers.transfer(key, request, INTERNAL);

        assertEquals(savedId, resumed.getId(), "the retry resumes the transfer the first attempt saved");
        assertEquals(1, transactions.findBySenderAccountNumberOrderByCreatedAtDesc(sender).size(), "no second transfer");
        ArgumentCaptor<LedgerEntryRequest> holds = ArgumentCaptor.forClass(LedgerEntryRequest.class);
        verify(ledger, times(2)).post(holds.capture());
        holds.getAllValues().forEach(hold -> assertEquals("tx-" + savedId + "-hold", hold.idempotencyKey(),
                "both attempts use the same hold key, so the ledger moves the money once"));
    }

    @Test
    void aRefusedHoldIsReplayedAsTheSameRefusalWithoutAnotherLedgerCall() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        when(ledger.post(any())).thenThrow(FeignException.errorStatus("LedgerClient#post",
                failure(422, "{\"detail\":\"Insufficient funds\"}")));
        TransactionRequest request = request(sender, receiver, "100.00");
        String key = UUID.randomUUID().toString();

        assertThrows(FeignException.class, () -> transfers.transfer(key, request, INTERNAL));
        TransactionRejectedException repeat = assertThrows(TransactionRejectedException.class,
                () -> transfers.transfer(key, request, INTERNAL));

        assertEquals("Hold refused by account-service (422)", repeat.getMessage());
        verify(ledger, times(1)).post(any());
    }

    @Test
    void theKeyIsRequired() {
        String sender = randomAccount();
        String receiver = randomAccount();

        assertThrows(IllegalArgumentException.class, () -> transfers.transfer(" ", request(sender, receiver, "10.00"), INTERNAL));
        verify(ledger, times(0)).post(any());
    }

    // ------------------------------------------------------------------ helpers

    private void stubAccounts(String sender, String receiver) {
        when(accountService.getAccount(sender)).thenReturn(new AccountInfo(sender, "owner-" + sender, "ACTIVE"));
        when(accountService.getAccount(receiver)).thenReturn(new AccountInfo(receiver, "owner-" + receiver, "ACTIVE"));
    }

    private static TransactionRequest request(String sender, String receiver, String amount) {
        TransactionRequest request = new TransactionRequest();
        request.setSenderAccountNumber(sender);
        request.setReceiverAccountNumber(receiver);
        request.setAmount(new java.math.BigDecimal(amount));
        request.setType(TransactionType.TRANSFER);
        request.setDescription("idempotency test");
        return request;
    }

    private static String randomAccount() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }

    private static Response failure(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "http://account-service/internal/v1/ledger/entries",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().status(status).reason("refused").request(request).headers(Map.of())
                .body(body, StandardCharsets.UTF_8).build();
    }
}
