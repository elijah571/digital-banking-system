package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.client.AccountInfo;
import org.elibanking.transactionservice.client.AccountService;
import org.elibanking.transactionservice.client.LedgerClient;
import org.elibanking.transactionservice.dto.TransactionRequest;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.entity.TransactionType;
import org.elibanking.transactionservice.exception.TransactionAccessDeniedException;
import org.elibanking.transactionservice.security.Caller;
import org.elibanking.transactionservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TRANSFER_PIN_REQUIRED on (wallet-plan 8.22): every transfer needs a PIN the verifier accepts, and nothing moves otherwise. */
@SpringBootTest(properties = {"internal-auth.jwt-secret=transaction-service-test-secret-0123456789", "otp.hmac-key=transaction-service-test-otp-hmac-key-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET, "outbox.relay.enabled=false", "transfer.recovery.enabled=false",
        "transfer.pin-required=true"})
@Import(TestcontainersConfiguration.class)
class PinRequiredTest {

    @Autowired
    TransactionalService transfers;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    LedgerClient ledger;

    @MockitoBean
    PinVerifier verifier;

    @Test
    void aTransferWithoutAPinIsRefusedBeforeAnyMoneyMoves() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);

        assertThrows(IllegalArgumentException.class,
                () -> transfers.transfer(request(sender, receiver, null), owner(sender)));
        verify(ledger, never()).post(any());
        verify(verifier, never()).check(any(), any());
    }

    @Test
    void aWrongPinIsRefusedAndNothingMoves() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        doThrow(new TransactionAccessDeniedException("Wrong PIN")).when(verifier).check("owner-" + sender, "0000");

        assertThrows(TransactionAccessDeniedException.class,
                () -> transfers.transfer(request(sender, receiver, "0000"), owner(sender)));
        verify(ledger, never()).post(any());
    }

    @Test
    void theRightPinLetsTheTransferProceed() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);
        doNothing().when(verifier).check("owner-" + sender, "1234");
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));

        assertEquals(TransactionStatus.PROCESSING,
                transfers.transfer(request(sender, receiver, "1234"), owner(sender)).getStatus());
        verify(verifier).check("owner-" + sender, "1234");
        verify(ledger).post(any());
    }

    @Test
    void anInternalCallerHasNoPinToCheckAndIsRefused() {
        String sender = randomAccount();
        String receiver = randomAccount();
        stubAccounts(sender, receiver);

        assertThrows(TransactionAccessDeniedException.class,
                () -> transfers.transfer(request(sender, receiver, "1234"), new Caller(null, null, null)));
        verify(ledger, never()).post(any());
    }

    private void stubAccounts(String sender, String receiver) {
        when(accountService.getAccount(sender)).thenReturn(new AccountInfo(sender, "owner-" + sender, "ACTIVE"));
        when(accountService.getAccount(receiver)).thenReturn(new AccountInfo(receiver, "owner-" + receiver, "ACTIVE"));
    }

    private static Caller owner(String sender) {
        return new Caller("owner-" + sender, null, "USER");
    }

    private static TransactionRequest request(String sender, String receiver, String pin) {
        TransactionRequest request = new TransactionRequest();
        request.setSenderAccountNumber(sender);
        request.setReceiverAccountNumber(receiver);
        request.setAmount(new BigDecimal("100.00"));
        request.setType(TransactionType.TRANSFER);
        request.setPin(pin);
        return request;
    }

    private static String randomAccount() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }
}
