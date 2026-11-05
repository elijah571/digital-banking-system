package org.elibanking.accountservice.service;

import org.elibanking.accountservice.TestcontainersConfiguration;
import org.elibanking.accountservice.dto.AccountResponse;
import org.elibanking.accountservice.dto.CreateAccountRequest;
import org.elibanking.accountservice.exception.InvalidOpeningDepositException;
import org.elibanking.accountservice.security.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Opening deposits off, as in prod: a deposit is refused, and an account opens at zero with its wallet (wallet-plan 3.4, D4). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountOpeningOffTest {

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aNonZeroDepositIsRefusedAndNothingIsCreated() {
        CreateAccountRequest request = AccountOpeningTest.request("1.00");
        String email = request.getEmail();

        assertThrows(InvalidOpeningDepositException.class, () -> accounts.createAccount(request, Caller.internal()));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE email = ?", Long.class, email),
                "a refused deposit creates no account");
    }

    @Test
    void aDepositWithMoreThanTwoDecimalsIsRefused() {
        assertThrows(InvalidOpeningDepositException.class,
                () -> accounts.createAccount(AccountOpeningTest.request("1.005"), Caller.internal()));
    }

    @Test
    void anAccountWithNoDepositOpensAtZeroWithAWallet() {
        AccountResponse opened = accounts.createAccount(AccountOpeningTest.request(null), Caller.internal());
        String id = jdbc.queryForObject("SELECT id FROM accounts WHERE account_number = ?", String.class,
                opened.getAccountNumber());

        assertNotNull(jdbc.queryForObject("SELECT ledger_account_id FROM accounts WHERE id = ?", Long.class, id));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                "SELECT la.balance FROM accounts a JOIN ledger_account la ON la.id = a.ledger_account_id WHERE a.id = ?",
                BigDecimal.class, id)));
    }
}
