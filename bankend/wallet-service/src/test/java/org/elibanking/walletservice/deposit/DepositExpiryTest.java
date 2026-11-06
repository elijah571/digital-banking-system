package org.elibanking.walletservice.deposit;

import org.elibanking.walletservice.TestcontainersConfiguration;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.exception.RequestRefusedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deposit expiry (wallet-plan 3.5, step 6), with real MySQL. Stripe is reached only through the mocked payment-service client.
 * A deposit becomes EXPIRED only after its intent is cancelled, so a late success cannot land on an expired deposit.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DepositExpiryTest {

    @Autowired
    DepositExpiryService expiry;

    @Autowired
    DepositRepository deposits;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    PaymentServiceClient payments;

    @Test
    void aDueDepositWhoseIntentStripeCancelsExpires() {
        Deposit deposit = due("pi_expire_1");
        when(payments.cancelIntent("pi_expire_1")).thenReturn(true);

        assertTrue(expiry.expire(deposit.getId()));

        assertEquals("EXPIRED", statusOf(deposit.getId()));
        assertEquals(1, auditRows(deposit.getId(), "DEPOSIT_EXPIRED"));
    }

    @Test
    void aRefusedCancelLeavesTheDepositPending() {
        Deposit deposit = due("pi_refused_1");
        when(payments.cancelIntent("pi_refused_1")).thenReturn(false);

        assertFalse(expiry.expire(deposit.getId()));

        assertEquals("PENDING", statusOf(deposit.getId()));
        assertEquals(0, auditRows(deposit.getId(), "DEPOSIT_EXPIRED"));
    }

    @Test
    void anUnreachablePaymentServiceLeavesTheDepositPendingUntilTheNextRun() {
        Deposit deposit = due("pi_down_1");
        when(payments.cancelIntent("pi_down_1"))
                .thenThrow(new RequestRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "down"))
                .thenReturn(true);

        assertFalse(expiry.expire(deposit.getId()));
        assertEquals("PENDING", statusOf(deposit.getId()));

        assertTrue(expiry.expire(deposit.getId()));
        assertEquals("EXPIRED", statusOf(deposit.getId()));
    }

    @Test
    void aDepositWithNoIntentExpiresWithoutAnyCancel() {
        Deposit deposit = due(null);

        assertTrue(expiry.expire(deposit.getId()));

        assertEquals("EXPIRED", statusOf(deposit.getId()));
        verify(payments, never()).cancelIntent(isNull());
    }

    @Test
    void aDepositCompletedWhileTheCancelRunsIsNotExpired() {
        Deposit deposit = due("pi_race_1");
        when(payments.cancelIntent("pi_race_1")).thenAnswer(invocation -> {
            // A webhook completes the deposit while the cancel is in flight.
            jdbc.update("UPDATE deposit SET status = 'COMPLETED' WHERE id = ?", deposit.getId());
            return true;
        });

        assertFalse(expiry.expire(deposit.getId()));

        assertEquals("COMPLETED", statusOf(deposit.getId()));
        assertEquals(0, auditRows(deposit.getId(), "DEPOSIT_EXPIRED"));
    }

    @Test
    void theBatchExpiresOnlyTheDepositsWhoseTimeHasRunOut() {
        Deposit due = due("pi_batch_1");
        Deposit notYet = save("pi_batch_2", Instant.now().plus(Duration.ofHours(1)));
        when(payments.cancelIntent("pi_batch_1")).thenReturn(true);

        expiry.expireDue();

        assertEquals("EXPIRED", statusOf(due.getId()));
        assertEquals("PENDING", statusOf(notYet.getId()));
        verify(payments, never()).cancelIntent("pi_batch_2");
    }

    // ------------------------------------------------------------------ helpers

    private Deposit due(String intentId) {
        return save(intentId, Instant.now().minus(Duration.ofMinutes(1)));
    }

    private Deposit save(String intentId, Instant expiresAt) {
        Deposit deposit = Deposit.pending(UUID.randomUUID().toString(), "user-" + UUID.randomUUID(),
                String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L)),
                new BigDecimal("20000.00"), "NGN", expiresAt);
        deposit.setPaymentIntentId(intentId);
        return deposits.save(deposit);
    }

    private String statusOf(String depositId) {
        return jdbc.queryForObject("SELECT status FROM deposit WHERE id = ?", String.class, depositId);
    }

    private int auditRows(String depositId, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'deposit' AND entity_id = ? AND action = ?",
                Integer.class, depositId, action);
    }
}
