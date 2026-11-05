package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.client.AccountInfo;
import org.elibanking.transactionservice.client.AccountService;
import org.elibanking.transactionservice.client.LedgerClient;
import org.elibanking.transactionservice.dto.TransactionRequest;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.entity.TransactionType;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TRANSFER_PIN_REQUIRED off (the default, wallet-plan 8.22): the PIN is not checked and the verifier is never reached. */
@SpringBootTest(properties = {"internal-auth.jwt-secret=transaction-service-test-secret-0123456789", "otp.hmac-key=transaction-service-test-otp-hmac-key-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET, "outbox.relay.enabled=false", "transfer.recovery.enabled=false"})
@Import(TestcontainersConfiguration.class)
class PinGateTest {

    @Autowired
    TransactionalService transfers;

    @MockitoBean
    AccountService accountService;

    @MockitoBean
    LedgerClient ledger;

    @MockitoBean
    PinVerifier verifier;

    @Test
    void withThePinRequirementOffTheVerifierIsNeverCalled() {
        String sender = randomAccount();
        String receiver = randomAccount();
        when(accountService.getAccount(sender)).thenReturn(new AccountInfo(sender, "owner-" + sender, "ACTIVE"));
        when(accountService.getAccount(receiver)).thenReturn(new AccountInfo(receiver, "owner-" + receiver, "ACTIVE"));
        when(ledger.post(any())).thenReturn(new LedgerClient.LedgerEntryResponse(1L, false));

        TransactionRequest request = request(sender, receiver);
        request.setPin("1234");

        assertEquals(TransactionStatus.PROCESSING,
                transfers.transfer(request, new Caller("owner-" + sender, null, "USER")).getStatus());
        verify(verifier, never()).check(any(), any());
    }

    private static TransactionRequest request(String sender, String receiver) {
        TransactionRequest request = new TransactionRequest();
        request.setSenderAccountNumber(sender);
        request.setReceiverAccountNumber(receiver);
        request.setAmount(new BigDecimal("100.00"));
        request.setType(TransactionType.TRANSFER);
        return request;
    }

    private static String randomAccount() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }
}
