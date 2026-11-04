package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CardDepositPostingsTest {

    @Autowired
    CardDepositPostings deposits;

    @Autowired
    LedgerPostingService ledger;

    @Autowired
    JdbcTemplate jdbc;

    private long clearing;
    private long suspense;
    private long fees;

    @BeforeEach
    void systemAccounts() {
        clearing = systemAccount("PROVIDER_CLEARING:STRIPE:NGN");
        suspense = systemAccount("DEPOSIT_SUSPENSE:NGN");
        fees = systemAccount("PAYMENT_FEES:NGN");
    }

    @Test
    void exactAmountCreditsTheWalletAndLeavesSuspenseUntouched() {
        long wallet = wallet();
        String deposit = UUID.randomUUID().toString();
        BigDecimal suspenseBefore = balance(suspense);

        var outcome = deposits.settle(deposit, wallet, settlement("1000.00", "1000.00"));

        assertEquals(DepositSettlement.Outcome.COMPLETED, outcome);
        assertEquals(0, balance(wallet).compareTo(new BigDecimal("1000.00")));
        assertEquals(0, balance(suspense).compareTo(suspenseBefore));
        assertEquals(0, count("dep-" + deposit + "-suspense"), "no suspense entry when nothing is left over");
        LedgerAssertions.assertReconciles(jdbc);
    }

    @Test
    void surplusCreditsTheRequestedAmountAndSuspensesTheRest() {
        long wallet = wallet();
        String deposit = UUID.randomUUID().toString();
        BigDecimal clearingBefore = balance(clearing);
        BigDecimal suspenseBefore = balance(suspense);

        var outcome = deposits.settle(deposit, wallet, settlement("1000.00", "1050.00"));

        assertEquals(DepositSettlement.Outcome.COMPLETED, outcome);
        assertEquals(0, balance(wallet).compareTo(new BigDecimal("1000.00")), "the wallet gets exactly the request");
        assertEquals(0, balance(suspense).subtract(suspenseBefore).compareTo(new BigDecimal("50.00")),
                "suspense gets the surplus");
        assertEquals(0, balance(clearing).subtract(clearingBefore).compareTo(new BigDecimal("1050.00")),
                "clearing holds everything that arrived");
        LedgerAssertions.assertReconciles(jdbc);
    }

    @Test
    void shortfallCreditsNothingAndSuspensesWhatArrived() {
        long wallet = wallet();
        String deposit = UUID.randomUUID().toString();
        BigDecimal suspenseBefore = balance(suspense);

        var outcome = deposits.settle(deposit, wallet, settlement("1000.00", "600.00"));

        assertEquals(DepositSettlement.Outcome.FAILED, outcome, "the deposit is FAILED for the user");
        assertEquals(0, balance(wallet).compareTo(BigDecimal.ZERO), "the wallet gets nothing");
        assertEquals(0, balance(suspense).subtract(suspenseBefore).compareTo(new BigDecimal("600.00")),
                "what arrived still goes to suspense");
        LedgerAssertions.assertReconciles(jdbc);
    }

    @Test
    void replayingTheSameDepositDoesNotCreditOrSuspenseTwice() {
        long wallet = wallet();
        String deposit = UUID.randomUUID().toString();
        BigDecimal suspenseBefore = balance(suspense);

        deposits.settle(deposit, wallet, settlement("1000.00", "1050.00"));
        deposits.settle(deposit, wallet, settlement("1000.00", "1050.00"));

        assertEquals(0, balance(wallet).compareTo(new BigDecimal("1000.00")));
        assertEquals(0, balance(suspense).subtract(suspenseBefore).compareTo(new BigDecimal("50.00")));
        LedgerAssertions.assertReconciles(jdbc);
    }

    @Test
    void feeIsItsOwnEntryAndNeverTouchesTheWallet() {
        long wallet = wallet();
        String deposit = UUID.randomUUID().toString();
        deposits.settle(deposit, wallet, settlement("1000.00", "1000.00"));
        BigDecimal feesBefore = balance(fees);
        BigDecimal clearingBefore = balance(clearing);
        BigDecimal fee = StripeFees.toNgn(new BigDecimal("0.33"), "usd", new BigDecimal("0.000710374")).amount();

        deposits.recordFee(deposit, Money.of(fee, "NGN"));
        deposits.recordFee(deposit, Money.of(fee, "NGN"));

        assertEquals(0, balance(wallet).compareTo(new BigDecimal("1000.00")), "the wallet is unchanged by the fee");
        assertEquals(0, balance(fees).subtract(feesBefore).compareTo(new BigDecimal("464.54")),
                "PAYMENT_FEES grows once, on the replay too");
        assertEquals(0, balance(clearing).subtract(clearingBefore).compareTo(new BigDecimal("-464.54")));
        LedgerAssertions.assertReconciles(jdbc);
    }

    @Test
    void theCreditDoesNotWaitForFeeData() {
        long wallet = wallet();
        String deposit = UUID.randomUUID().toString();

        // No fee entry exists yet: the credit posts on its own.
        deposits.settle(deposit, wallet, settlement("1000.00", "1000.00"));

        assertEquals(0, balance(wallet).compareTo(new BigDecimal("1000.00")));
        assertEquals(0, count("dep-" + deposit + "-fee"));
    }

    @Test
    void aReplayThatChangesTheRequestedAmountIsAConflictAndWritesNothing() {
        long wallet = wallet();
        String deposit = UUID.randomUUID().toString();
        deposits.settle(deposit, wallet, settlement("1000.00", "1000.00"));
        BigDecimal suspenseBefore = balance(suspense);

        // The credit entry for this deposit already exists with 1000.00, so 1100.00 under the same key is a conflict.
        assertThrows(IdempotencyConflictException.class,
                () -> deposits.settle(deposit, wallet, settlement("1100.00", "1100.00")));

        assertEquals(0, balance(wallet).compareTo(new BigDecimal("1000.00")), "the credit is not applied twice");
        assertEquals(0, balance(suspense).compareTo(suspenseBefore), "nothing is booked to suspense either");
        LedgerAssertions.assertReconciles(jdbc);
    }

    // ------------------------------------------------------------------ helpers

    private static DepositSettlement settlement(String requested, String received) {
        return new DepositSettlement(Money.of(requested, "NGN"), Money.of(received, "NGN"));
    }

    private long wallet() {
        String ownerRef = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ledger_account (owner_type, owner_ref, type, currency) VALUES ('USER', ?, 'LIABILITY', 'NGN')",
                ownerRef);
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE owner_ref = ?", Long.class, ownerRef);
    }

    private long systemAccount(String code) {
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class, code);
    }

    private BigDecimal balance(long ledgerAccountId) {
        return jdbc.queryForObject("SELECT balance FROM ledger_account WHERE id = ?", BigDecimal.class, ledgerAccountId);
    }

    private long count(String idempotencyKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?", Long.class,
                idempotencyKey);
    }
}
