package org.elibanking.accountservice.idempotency;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.accountservice.ledger.LedgerPostingService;
import org.elibanking.accountservice.ledger.PostingLine;
import org.elibanking.accountservice.ledger.PostingRequest;
import org.elibanking.accountservice.ledger.PostingResult;
import org.elibanking.accountservice.ledger.Side;
import org.elibanking.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyServiceTest {

    private static final Duration LONG_LEASE = Duration.ofSeconds(30);

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    LedgerPostingService ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void completedRequestIsReplayedWithItsStoredResponse() {
        String scope = scope();
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.hash("amount=10&to=B");

        assertTrue(idempotency.begin(scope, key, hash, LONG_LEASE).isEmpty());
        idempotency.complete(scope, key, 201, "{\"entryId\":42}");

        Optional<StoredResponse> replay = idempotency.begin(scope, key, hash, LONG_LEASE);
        assertTrue(replay.isPresent());
        assertEquals(201, replay.get().status());
        assertTrue(replay.get().bodyJson().contains("42"));
    }

    @Test
    void theSameKeyWithADifferentRequestIsRejected() {
        String scope = scope();
        String key = UUID.randomUUID().toString();
        idempotency.begin(scope, key, IdempotencyService.hash("amount=10"), LONG_LEASE);

        assertThrows(IdempotencyKeyMismatchException.class,
                () -> idempotency.begin(scope, key, IdempotencyService.hash("amount=11"), LONG_LEASE));
    }

    @Test
    void aLiveLeaseBlocksASecondAttempt() {
        String scope = scope();
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.hash("amount=10");
        idempotency.begin(scope, key, hash, LONG_LEASE);

        assertThrows(IdempotencyInProgressException.class, () -> idempotency.begin(scope, key, hash, LONG_LEASE));
    }

    @Test
    void anExpiredLeaseCanBeTakenOver() throws InterruptedException {
        String scope = scope();
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.hash("amount=10");
        idempotency.begin(scope, key, hash, Duration.ofMillis(200));

        Thread.sleep(500);

        assertTrue(idempotency.begin(scope, key, hash, LONG_LEASE).isEmpty(), "the second attempt runs the handler");
    }

    /**
     * Fix 2: the handler commits its ledger entry, then the process dies before the record is marked DONE. After the
     * lease expires, the retry takes the key over, and the deterministic ledger key returns the original entry. Money
     * moves once, and the record ends DONE with the same resource.
     */
    @Test
    void aCrashAfterTheLedgerCommitDoesNotMoveMoneyTwice() throws InterruptedException {
        String scope = scope();
        String key = UUID.randomUUID().toString();
        String ledgerKey = "transfer-" + key;
        String hash = IdempotencyService.hash("amount=25&to=wallet");
        long wallet = wallet();
        long payable = systemAccount("PAYOUT_PAYABLE:NGN");

        // attempt 1: claims the key, commits the entry, and dies before complete()
        assertTrue(idempotency.begin(scope, key, hash, Duration.ofMillis(300)).isEmpty());
        PostingResult first = ledger.post(PostingRequest.of("TRANSFER", ledgerKey, List.of(
                new PostingLine(payable, Side.DEBIT, Money.of("25.00", "NGN")),
                new PostingLine(wallet, Side.CREDIT, Money.of("25.00", "NGN")))));
        assertFalse(first.replayed());

        Thread.sleep(600);

        // attempt 2: takes the key over and runs the handler again
        assertTrue(idempotency.begin(scope, key, hash, LONG_LEASE).isEmpty());
        PostingResult second = ledger.post(PostingRequest.of("TRANSFER", ledgerKey, List.of(
                new PostingLine(payable, Side.DEBIT, Money.of("25.00", "NGN")),
                new PostingLine(wallet, Side.CREDIT, Money.of("25.00", "NGN")))));
        idempotency.complete(scope, key, 201, "{\"entryId\":" + second.entryId() + "}");

        assertEquals(first.entryId(), second.entryId(), "the retry returns the same resource");
        assertTrue(second.replayed());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?", Long.class, ledgerKey));
        assertEquals(0, jdbc.queryForObject("SELECT balance FROM ledger_account WHERE id = ?", BigDecimal.class, wallet)
                .compareTo(new BigDecimal("25.00")), "the wallet was credited once");
        assertEquals("DONE", jdbc.queryForObject(
                "SELECT status FROM idempotency_record WHERE scope = ? AND idem_key = ?", String.class, scope, key));
    }

    // ------------------------------------------------------------------ helpers

    private static String scope() {
        return "user-" + UUID.randomUUID() + "/test";
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
}
