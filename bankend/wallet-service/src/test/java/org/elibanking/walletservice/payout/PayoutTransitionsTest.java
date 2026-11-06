package org.elibanking.walletservice.payout;

import org.elibanking.walletservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The one writer of payout state (wallet-plan 8.3), with real MySQL: a move is checked, audited, put on the outbox, and gives the
 * daily usage back when it ends the payout without a settle.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PayoutTransitionsTest {

    @Autowired
    PayoutTransitions transitions;

    @Autowired
    OutboundPayoutRepository payouts;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aRejectionGivesTheDailyUsageBackAndLeavesAuditAndOutboxRows() {
        String account = newAccount();
        long id = openPayout(account, "2000.00");

        tx.executeWithoutResult(status -> transitions.move(payouts.findById(id).orElseThrow(), PayoutState.PENDING_APPROVAL,
                "amount is above the approval threshold"));
        assertEquals("2000.00", usage(account), "a payout waiting for approval still counts");

        tx.executeWithoutResult(status -> transitions.move(payouts.findById(id).orElseThrow(), PayoutState.REJECTED,
                "the approver said no"));
        assertEquals("0.00", usage(account), "a rejection gives the allowance back");
        assertEquals("REJECTED", stateOf(id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'outbound_payout'"
                + " AND entity_id = ? AND action = 'PAYOUT_REJECTED'", Integer.class, String.valueOf(id)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE message_key = ? AND topic = 'wallet.payout.rejected'",
                Integer.class, "out-" + id));
    }

    @Test
    void aRefusedMoveChangesNothing() {
        String account = newAccount();
        long id = openPayout(account, "500.00");
        tx.executeWithoutResult(status -> transitions.move(payouts.findById(id).orElseThrow(), PayoutState.FAILED,
                "no funds"));
        assertEquals("0.00", usage(account));

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status ->
                transitions.move(payouts.findById(id).orElseThrow(), PayoutState.APPROVED, "a second try")));

        assertEquals("FAILED", stateOf(id), "the refused move is not applied");
        assertEquals("0.00", usage(account), "and it gives nothing back a second time");
    }

    @Test
    void aSettledPayoutThatIsReversedGivesItBack() {
        String account = newAccount();
        long id = openPayout(account, "750.00");
        tx.executeWithoutResult(status -> {
            OutboundPayout p = payouts.findById(id).orElseThrow();
            transitions.move(p, PayoutState.APPROVED, "approved");
            transitions.move(p, PayoutState.PROCESSING, "sent");
            transitions.move(p, PayoutState.COMPLETED, "the provider confirmed success");
        });
        assertEquals("750.00", usage(account), "a settled payout keeps counting");

        tx.executeWithoutResult(status -> transitions.move(payouts.findById(id).orElseThrow(), PayoutState.REVERSED,
                "the bank reversed the transfer"));
        assertEquals("0.00", usage(account), "D21: a reversed payout gives its allowance back");
    }

    /** A payout in REQUESTED with its day's usage opened, as the withdrawal request will do (6.2). */
    private long openPayout(String account, String amount) {
        OutboundPayout p = new OutboundPayout();
        p.setType(PayoutType.WITHDRAWAL);
        p.setUserId("user-" + account);
        p.setAccountNumber(account);
        p.setAmount(new BigDecimal(amount));
        p.setFee(BigDecimal.ZERO);
        p.setCurrency("NGN");
        p.setBankCode("044");
        p.setDestinationAccountNumber("0123456789");
        p.setState(PayoutState.REQUESTED);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        long id = payouts.save(p).getId();
        jdbc.update("INSERT INTO outbound_daily_usage (account_number, usage_day, total) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE total = total + VALUES(total)", account,
                LocalDate.ofInstant(p.getCreatedAt(), PayoutTransitions.BUSINESS_ZONE), new BigDecimal(amount));
        return id;
    }

    private String usage(String account) {
        return jdbc.queryForObject("SELECT total FROM outbound_daily_usage WHERE account_number = ? AND usage_day = ?",
                BigDecimal.class, account, LocalDate.now(PayoutTransitions.BUSINESS_ZONE)).setScale(2).toPlainString();
    }

    private String stateOf(long id) {
        return jdbc.queryForObject("SELECT state FROM outbound_payout WHERE id = ?", String.class, id);
    }

    private static String newAccount() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }
}
