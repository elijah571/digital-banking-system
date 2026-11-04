package org.elibanking.accountservice.ledger;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.accountservice.dto.AccountResponse;
import org.elibanking.accountservice.dto.CreateAccountRequest;
import org.elibanking.accountservice.entity.AccountType;
import org.elibanking.accountservice.security.Caller;
import org.elibanking.accountservice.service.AccountService;
import org.elibanking.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A release is a refund, and a refund is never refused (wallet-plan 3.4, 8.27). After the wrong OTP's fraud event blocks the
 * sender, the release still posts: the sender gets the money back and TRANSFER_IN_FLIGHT returns to where it started.
 */
@SpringBootTest(properties = "account.opening-deposit.enabled=true")
@Import(TestcontainersConfiguration.class)
class BlockedAccountReleaseTest {

    private static final String IN_FLIGHT = "TRANSFER_IN_FLIGHT:NGN";

    @Autowired
    AccountService accounts;

    @Autowired
    ServicePostings postings;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aReleaseToABlockedSenderLandsAndTheMoneyComesBack() {
        String id = idOf(accounts.createAccount(request("1000.00"), Caller.internal()));
        String number = jdbc.queryForObject("SELECT account_number FROM accounts WHERE id = ?", String.class, id);
        long wallet = jdbc.queryForObject("SELECT ledger_account_id FROM accounts WHERE id = ?", Long.class, id);
        long inFlight = systemAccount(IN_FLIGHT);
        BigDecimal inFlightBefore = balanceOf(inFlight);
        String txId = "tx-" + UUID.randomUUID();

        // The hold: the sender's wallet pays into TRANSFER_IN_FLIGHT.
        postings.post(PostingRequest.of("TRANSFER_HOLD", txId + "-hold", List.of(
                        new PostingLine(wallet, Side.DEBIT, Money.of("250.00", "NGN")),
                        new PostingLine(inFlight, Side.CREDIT, Money.of("250.00", "NGN")))),
                "transaction-service", "req-" + UUID.randomUUID());
        assertEquals(0, new BigDecimal("750").compareTo(walletBalance(id)), "the hold takes the money out of the wallet");

        // The wrong OTP's fraud.detected event blocks the sender before the refund is posted.
        accounts.blockAccount(number);
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT account_status FROM accounts WHERE id = ?", String.class, id));

        PostingResult release = postings.post(PostingRequest.of("TRANSFER_RELEASE", txId + "-release", List.of(
                        new PostingLine(inFlight, Side.DEBIT, Money.of("250.00", "NGN")),
                        new PostingLine(wallet, Side.CREDIT, Money.of("250.00", "NGN")))),
                "transaction-service", "req-" + UUID.randomUUID());

        assertFalse(release.replayed(), "the release posts for the first time, not as a replay");
        assertEquals(0, new BigDecimal("1000").compareTo(walletBalance(id)), "the blocked sender's balance is restored");
        assertEquals(0, inFlightBefore.compareTo(balanceOf(inFlight)), "TRANSFER_IN_FLIGHT is back to where it started");
        assertTrue(reconciliation.check().clean(), () -> "issues: " + reconciliation.check().issues());
    }

    // ------------------------------------------------------------------ helpers

    private String idOf(AccountResponse opened) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE account_number = ?", String.class,
                opened.getAccountNumber());
    }

    private BigDecimal walletBalance(String accountId) {
        return jdbc.queryForObject("SELECT la.balance FROM accounts a JOIN ledger_account la"
                + " ON la.id = a.ledger_account_id WHERE a.id = ?", BigDecimal.class, accountId);
    }

    private long systemAccount(String code) {
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class, code);
    }

    private BigDecimal balanceOf(long ledgerAccountId) {
        return jdbc.queryForObject("SELECT balance FROM ledger_account WHERE id = ?", BigDecimal.class, ledgerAccountId);
    }

    private static CreateAccountRequest request(String deposit) {
        CreateAccountRequest request = new CreateAccountRequest();
        request.setAccountHolderName("Blocked Release Test");
        request.setEmail("blocked-release-" + UUID.randomUUID() + "@example.test");
        request.setPhone("082" + String.format("%08d", ThreadLocalRandom.current().nextLong(100_000_000L)));
        request.setAccountType(AccountType.SAVINGS);
        request.setInitialDeposit(new BigDecimal(deposit));
        return request;
    }
}
