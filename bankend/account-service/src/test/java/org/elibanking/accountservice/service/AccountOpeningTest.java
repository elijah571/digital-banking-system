package org.elibanking.accountservice.service;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.accountservice.dto.AccountResponse;
import org.elibanking.accountservice.dto.CreateAccountRequest;
import org.elibanking.accountservice.entity.AccountType;
import org.elibanking.accountservice.ledger.ReconciliationService;
import org.elibanking.accountservice.security.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Opening deposits on, as in dev: the wallet is created with the account, and the deposit is posted through the ledger
 * (wallet-plan 3.4, D4). The reconciliation check must be clean afterwards.
 */
@SpringBootTest(properties = "account.opening-deposit.enabled=true")
@Import(TestcontainersConfiguration.class)
class AccountOpeningTest {

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void anOpenedAccountHasItsWalletAndTheDepositPostedThroughTheLedger() {
        var reconciliationBefore = reconciliation.check();
        String id = idOf(accounts.createAccount(request("1500.00"), Caller.internal()));

        assertNotNull(jdbc.queryForObject("SELECT ledger_account_id FROM accounts WHERE id = ?", Long.class, id),
                "the account is linked to its wallet");
        assertEquals(0, new BigDecimal("1500").compareTo(walletBalance(id)), "the wallet holds the deposit");
        assertEquals(0, new BigDecimal("1500").compareTo(jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id)), "accounts.balance mirrors the wallet");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?",
                Long.class, "acct-" + id + "-opening"), "the opening entry is posted once");
        var reconciliationAfter = reconciliation.check();
        assertEquals(reconciliationBefore.issues(), reconciliationAfter.issues(),
                "opening this account must not add a reconciliation issue");
    }

    @Test
    void aZeroDepositOpensTheAccountWithAWalletAndNoEntry() {
        String id = idOf(accounts.createAccount(request("0"), Caller.internal()));

        assertNotNull(jdbc.queryForObject("SELECT ledger_account_id FROM accounts WHERE id = ?", Long.class, id));
        assertEquals(0, BigDecimal.ZERO.compareTo(walletBalance(id)), "the wallet starts at zero");
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?",
                Long.class, "acct-" + id + "-opening"), "no opening entry without a deposit");
    }

    private String idOf(AccountResponse opened) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE account_number = ?", String.class,
                opened.getAccountNumber());
    }

    private BigDecimal walletBalance(String accountId) {
        return jdbc.queryForObject("SELECT la.balance FROM accounts a JOIN ledger_account la"
                + " ON la.id = a.ledger_account_id WHERE a.id = ?", BigDecimal.class, accountId);
    }

    static CreateAccountRequest request(String deposit) {
        CreateAccountRequest request = new CreateAccountRequest();
        request.setAccountHolderName("Opening Test");
        request.setEmail("opening-" + UUID.randomUUID() + "@example.test");
        request.setPhone("082" + String.format("%08d", ThreadLocalRandom.current().nextLong(100_000_000L)));
        request.setAccountType(AccountType.SAVINGS);
        request.setInitialDeposit(deposit == null ? null : new BigDecimal(deposit));
        return request;
    }
}
