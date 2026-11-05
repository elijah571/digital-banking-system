package org.elibanking.transactionservice.repository;

import org.elibanking.transactionservice.TestcontainersConfiguration;
import org.elibanking.transactionservice.entity.Transaction;
import org.elibanking.transactionservice.entity.TransactionStatus;
import org.elibanking.transactionservice.entity.TransactionType;
import org.elibanking.transactionservice.security.UserTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The version on {@link Transaction} (wallet-plan section 4, slice 3c.1). Two verifies that read the same version cannot
 * both write: the second write fails. The reads are sequenced here so the race is deterministic, not timing-dependent.
 */
@SpringBootTest(properties = {"internal-auth.jwt-secret=transaction-service-test-secret-0123456789", "otp.hmac-key=transaction-service-test-otp-hmac-key-0123456789",
        "auth.jwt-secret=" + UserTokens.SECRET, "outbox.relay.enabled=false", "transfer.recovery.enabled=false"})
@Import(TestcontainersConfiguration.class)
class TransactionVersionTest {

    @Autowired
    TransactionRepository transactions;

    @Test
    void theSecondWriterOfTheSameVersionLosesAndNothingIsOverwritten() {
        Transaction saved = transactions.saveAndFlush(newTransfer());
        assertEquals(0L, saved.getVersion(), "a new row starts at version 0");

        // Two verifies read the transfer before either writes.
        Transaction firstVerify = transactions.findById(saved.getId()).orElseThrow();
        Transaction secondVerify = transactions.findById(saved.getId()).orElseThrow();

        firstVerify.setStatus(TransactionStatus.COMPLETED);
        transactions.saveAndFlush(firstVerify);

        secondVerify.setStatus(TransactionStatus.FLAGGED);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> transactions.saveAndFlush(secondVerify),
                "a write based on version 0 must fail once version 1 exists");

        Transaction current = transactions.findById(saved.getId()).orElseThrow();
        assertEquals(TransactionStatus.COMPLETED, current.getStatus(), "the losing write did not overwrite the winner");
        assertEquals(1L, current.getVersion(), "one successful write, one version bump");
    }

    private static Transaction newTransfer() {
        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber("1000000001");
        transaction.setReceiverAccountNumber("1000000002");
        transaction.setReferenceNumber("REF-VERSION-TEST");
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
        transaction.setAmount(new BigDecimal("100.00"));
        return transaction;
    }
}
