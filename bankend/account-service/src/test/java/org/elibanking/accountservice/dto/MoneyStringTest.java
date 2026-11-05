package org.elibanking.accountservice.dto;

import org.elibanking.accountservice.entity.AccountStatus;
import org.elibanking.accountservice.entity.AccountType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Money in account responses is a JSON string with two decimal places (wallet-plan D3, 3c.8). Plain objects, no Spring context. */
class MoneyStringTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theBalanceAndTheLimitsAreWrittenAsStringsWithTwoDecimalPlaces() {
        AccountResponse response = new AccountResponse();
        response.setId("1");
        response.setAccountNumber("3693612260");
        response.setAccountType(AccountType.CURRENT);
        response.setStatus(AccountStatus.ACTIVE);
        response.setBalance(new BigDecimal("9000"));
        response.setInitialDeposit(new BigDecimal("500"));
        response.setDailyTransactionLimit(new BigDecimal("100000"));
        response.setCreatedAt(LocalDateTime.of(2026, 11, 5, 9, 0));

        String json = JSON.writeValueAsString(response);

        assertTrue(json.contains("\"balance\":\"9000.00\""), json);
        assertTrue(json.contains("\"initialDeposit\":\"500.00\""), json);
        assertTrue(json.contains("\"dailyTransactionLimit\":\"100000.00\""), json);
        assertFalse(json.contains("\"balance\":9000"), json);
    }

    @Test
    void plainTextKeepsTwoDecimalPlacesAndNeverRounds() {
        assertEquals("250.00", MoneyJson.plain(new BigDecimal("250")));
        assertEquals("250.50", MoneyJson.plain(new BigDecimal("250.5")));
        assertEquals("250.00", MoneyJson.plain(new BigDecimal("250.00")));
        assertEquals("10.001", MoneyJson.plain(new BigDecimal("10.001")), "more decimals are kept, not rounded");
    }
}
