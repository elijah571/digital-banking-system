package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerPostingServiceTest {

    private static final String NGN = "NGN";

    @Autowired
    LedgerPostingService ledger;

    @Autowired
    JdbcTemplate jdbc;

    private long openingEquity;
    private long payoutPayable;
    private long stripeClearing;

    @BeforeEach
    void systemAccounts() {
        openingEquity = systemAccount("OPENING_BALANCE_EQUITY:NGN");
        payoutPayable = systemAccount("PAYOUT_PAYABLE:NGN");
        stripeClearing = systemAccount("PROVIDER_CLEARING:STRIPE:NGN");
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void balancedEntryPostsAndUpdatesCachedBalances() {
        long a = wallet();
        long b = wallet();
        fund(a, "100.00");

        PostingResult result = transfer(a, b, "30.00", key());

        assertFalse(result.replayed());
        assertEquals(0, cmp(balance(a), "70.00"));
        assertEquals(0, cmp(balance(b), "30.00"));
        assertReconciles();
    }

    @Test
    void replayWithSameKeyAndContentReturnsTheOriginalEntry() {
        long a = wallet();
        long b = wallet();
        fund(a, "100.00");
        String key = key();

        PostingResult first = transfer(a, b, "30.00", key);
        PostingResult second = transfer(a, b, "30.00", key);

        assertEquals(first.entryId(), second.entryId());
        assertTrue(second.replayed());
        assertEquals(0, cmp(balance(a), "70.00"), "a replay must not move money twice");
        assertEquals(0, cmp(balance(b), "30.00"));
        assertReconciles();
    }

    @Test
    void sameKeyWithDifferentContentIsAConflict() {
        long a = wallet();
        long b = wallet();
        fund(a, "100.00");
        String key = key();
        transfer(a, b, "30.00", key);

        assertThrows(IdempotencyConflictException.class, () -> transfer(a, b, "31.00", key));
        assertEquals(0, cmp(balance(a), "70.00"));
        assertReconciles();
    }

    @Test
    void systemAccountMayGoNegative() {
        // A settled outbound payout credits PROVIDER_CLEARING before any deposit has funded it: allowed (3.2).
        // System accounts are shared by all tests, so assert the change, not the absolute balance.
        BigDecimal before = balance(stripeClearing);
        ledger.post(PostingRequest.of("PAYOUT_SETTLE", key(), List.of(
                line(payoutPayable, Side.DEBIT, "50.00"),
                line(stripeClearing, Side.CREDIT, "50.00"))));

        assertEquals(0, balance(stripeClearing).compareTo(before.subtract(new BigDecimal("50.00"))),
                "the posting must be accepted even though the account has no funds");
        assertReconciles();
    }

    // ------------------------------------------------------------------ rejections write nothing

    @Test
    void unbalancedEntryIsRejectedAndWritesNothing() {
        long a = wallet();
        long b = wallet();
        fund(a, "100.00");
        long entriesBefore = count("journal_entry");

        assertThrows(InvalidPostingException.class, () -> ledger.post(PostingRequest.of("TRANSFER", key(), List.of(
                line(a, Side.DEBIT, "10.00"),
                line(b, Side.CREDIT, "9.00")))));

        assertEquals(entriesBefore, count("journal_entry"));
        assertEquals(0, cmp(balance(a), "100.00"));
        assertEquals(0, cmp(balance(b), "0.00"));
    }

    @Test
    void zeroAmountLineIsRejected() {
        long a = wallet();
        long b = wallet();

        assertThrows(InvalidPostingException.class, () -> ledger.post(PostingRequest.of("TRANSFER", key(), List.of(
                line(a, Side.DEBIT, "0.00"),
                line(b, Side.CREDIT, "0.00")))));
    }

    @Test
    void customerWalletCannotGoNegative() {
        long a = wallet();
        long b = wallet();
        fund(a, "20.00");
        long entriesBefore = count("journal_entry");

        assertThrows(InsufficientFundsException.class, () -> transfer(a, b, "30.00", key()));

        assertEquals(entriesBefore, count("journal_entry"));
        assertEquals(0, cmp(balance(a), "20.00"));
        assertReconciles();
    }

    @Test
    void currencyMustMatchTheLedgerAccount() {
        long a = wallet();
        long b = wallet();
        fund(a, "10.00");

        assertThrows(InvalidPostingException.class, () -> ledger.post(PostingRequest.of("TRANSFER", key(), List.of(
                new PostingLine(a, Side.DEBIT, Money.of("5.00", "USD")),
                new PostingLine(b, Side.CREDIT, Money.of("5.00", "USD"))))));
    }

    // ------------------------------------------------------------------ reversal

    @Test
    void reversalRestoresBalancesAndAnEntryCanBeReversedOnce() {
        long a = wallet();
        long b = wallet();
        fund(a, "100.00");
        PostingResult original = transfer(a, b, "30.00", key());

        PostingResult reversal = ledger.reverse(original.entryId(), key(), "test");

        assertEquals(0, cmp(balance(a), "100.00"));
        assertEquals(0, cmp(balance(b), "0.00"));
        assertNotEquals(original.entryId(), reversal.entryId());
        assertThrows(AlreadyReversedException.class, () -> ledger.reverse(original.entryId(), key(), "test"));
        assertReconciles();
    }

    @Test
    void reversingAMissingEntryIsNotFound() {
        assertThrows(LedgerNotFoundException.class, () -> ledger.reverse(Long.MAX_VALUE, key(), "test"));
    }

    // ------------------------------------------------------------------ append-only journal

    @Test
    void journalRowsCannotBeUpdatedOrDeleted() {
        long a = wallet();
        long b = wallet();
        fund(a, "10.00");
        PostingResult posted = transfer(a, b, "1.00", key());

        DataAccessException update = assertThrows(DataAccessException.class, () ->
                jdbc.update("UPDATE journal_entry SET type = 'TAMPERED' WHERE id = ?", posted.entryId()));
        assertTrue(update.getMostSpecificCause().getMessage().contains("append-only"));

        DataAccessException delete = assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM journal_line WHERE entry_id = ?", posted.entryId()));
        assertTrue(delete.getMostSpecificCause().getMessage().contains("append-only"));
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void fiftyParallelDebitsNeverOverdrawAndTheLedgerBalances() throws Exception {
        long a = wallet();
        long b = wallet();
        fund(a, "100.00");

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> attempt(() -> transfer(a, b, "10.00", key())));
        }
        List<Outcome> outcomes = runTogether(tasks);

        long successes = outcomes.stream().filter(o -> o.kind == Kind.OK).count();
        long insufficient = outcomes.stream().filter(o -> o.kind == Kind.INSUFFICIENT).count();
        assertEquals(10, successes, "exactly 100 / 10 transfers can succeed");
        assertEquals(40, insufficient);
        assertEquals(0, cmp(balance(a), "0.00"));
        assertEquals(0, cmp(balance(b), "100.00"));
        assertReconciles();
    }

    @Test
    void oppositeTransfersRunningAtOnceDoNotDeadlock() throws Exception {
        long a = wallet();
        long b = wallet();
        fund(a, "500.00");
        fund(b, "500.00");

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            boolean forward = i % 2 == 0;
            tasks.add(() -> attempt(() -> forward
                    ? transfer(a, b, "10.00", key())
                    : transfer(b, a, "10.00", key())));
        }
        List<Outcome> outcomes = runTogether(tasks);

        assertTrue(outcomes.stream().allMatch(o -> o.kind == Kind.OK),
                "no deadlock or funds failure expected: " + outcomes.stream()
                        .filter(o -> o.kind != Kind.OK).map(o -> o.detail).toList());
        assertEquals(0, cmp(balance(a), "500.00"));
        assertEquals(0, cmp(balance(b), "500.00"));
        assertReconciles();
    }

    @Test
    void transfersAndWithdrawalsOnOneWalletNeverGoNegative() throws Exception {
        long a = wallet();
        long b = wallet();
        fund(a, "100.00");
        BigDecimal payoutBefore = balance(payoutPayable);

        // 25 internal transfers and 25 withdrawal holds of 10.00 each, against 100.00: exactly 10 may succeed.
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            tasks.add(() -> attempt(() -> transfer(a, b, "10.00", key())));
            tasks.add(() -> attempt(() -> ledger.post(PostingRequest.of("WITHDRAWAL_HOLD", key(), List.of(
                    line(a, Side.DEBIT, "10.00"),
                    line(payoutPayable, Side.CREDIT, "10.00"))))));
        }
        List<Outcome> outcomes = runTogether(tasks);

        long successes = outcomes.stream().filter(o -> o.kind == Kind.OK).count();
        assertEquals(10, successes);
        BigDecimal movedToTransfers = balance(b);
        BigDecimal movedToHolds = balance(payoutPayable).subtract(payoutBefore);
        assertEquals(0, movedToTransfers.add(movedToHolds).compareTo(new BigDecimal("100.00")),
                "every success moved exactly 10.00 out of the wallet");
        assertEquals(0, cmp(balance(a), "0.00"));
        assertReconciles();
    }

    // ------------------------------------------------------------------ helpers

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    private PostingResult transfer(long from, long to, String amount, String key) {
        return ledger.post(PostingRequest.of("TRANSFER", key, List.of(
                line(from, Side.DEBIT, amount),
                line(to, Side.CREDIT, amount))));
    }

    private void fund(long wallet, String amount) {
        ledger.post(PostingRequest.of("FUNDING", key(), List.of(
                line(openingEquity, Side.DEBIT, amount),
                line(wallet, Side.CREDIT, amount))));
    }

    private static PostingLine line(long account, Side side, String amount) {
        return new PostingLine(account, side, Money.of(amount, NGN));
    }

    /** A customer ledger account (test fixture: written directly, the service only reads it in these tests). */
    private long wallet() {
        String ownerRef = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ledger_account (owner_type, owner_ref, type, currency) VALUES ('USER', ?, 'LIABILITY', ?)",
                ownerRef, NGN);
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE owner_ref = ?", Long.class, ownerRef);
    }

    private long systemAccount(String code) {
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class, code);
    }

    private BigDecimal balance(long ledgerAccountId) {
        return jdbc.queryForObject("SELECT balance FROM ledger_account WHERE id = ?", BigDecimal.class, ledgerAccountId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static int cmp(BigDecimal actual, String expected) {
        return actual.compareTo(new BigDecimal(expected));
    }

    /**
     * The reconciliation identity from wallet-plan 3.2: each cached balance equals the signed sum of its lines, and
     * debits equal credits per currency across the whole journal.
     */
    private void assertReconciles() {
        LedgerAssertions.assertReconciles(jdbc);
    }

    // ------------------------------------------------------------------ concurrency helpers

    private enum Kind { OK, INSUFFICIENT, OTHER }

    private record Outcome(Kind kind, String detail) {
    }

    private static Outcome attempt(Callable<?> action) {
        try {
            action.call();
            return new Outcome(Kind.OK, "");
        } catch (InsufficientFundsException e) {
            return new Outcome(Kind.INSUFFICIENT, e.getMessage());
        } catch (Exception e) {
            return new Outcome(Kind.OTHER, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Releases all tasks at the same moment, so they really compete for the same rows. */
    private static List<Outcome> runTogether(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> task : tasks) {
                futures.add(pool.submit(() -> {
                    started.incrementAndGet();
                    start.await();
                    return task.call();
                }));
            }
            while (started.get() < tasks.size()) {
                Thread.sleep(5);
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }
            assertFalse(outcomes.stream().anyMatch(o -> o.kind == Kind.OTHER),
                    "unexpected failures: " + outcomes.stream().filter(o -> o.kind == Kind.OTHER)
                            .map(Outcome::detail).toList());
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
