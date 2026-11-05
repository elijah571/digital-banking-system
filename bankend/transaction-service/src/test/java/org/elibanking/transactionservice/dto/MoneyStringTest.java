package org.elibanking.transactionservice.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Money in responses is a JSON string with two decimal places (wallet-plan D3, 3c.8). Plain objects, no Spring context. */
class MoneyStringTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theTransferAmountIsWrittenAsAStringWithTwoDecimalPlaces() {
        TransactionResponse response = new TransactionResponse();
        response.setAmount(new BigDecimal("250"));

        String json = JSON.writeValueAsString(response);

        assertTrue(json.contains("\"amount\":\"250.00\""), json);
        assertFalse(json.contains("\"amount\":250"), json);
    }

    @Test
    void aStoredNumericAmountStillReadsBack() {
        TransactionResponse fromOldRecord = JSON.readValue("{\"amount\":250.00}", TransactionResponse.class);
        TransactionResponse fromNewRecord = JSON.readValue("{\"amount\":\"250.00\"}", TransactionResponse.class);

        assertEquals(0, new BigDecimal("250.00").compareTo(fromOldRecord.getAmount()));
        assertEquals(0, new BigDecimal("250.00").compareTo(fromNewRecord.getAmount()));
    }

    @Test
    void plainTextKeepsTwoDecimalPlacesAndNeverRounds() {
        assertEquals("250.00", MoneyJson.plain(new BigDecimal("250")));
        assertEquals("250.50", MoneyJson.plain(new BigDecimal("250.5")));
        assertEquals("250.00", MoneyJson.plain(new BigDecimal("250.00")));
        assertEquals("10.001", MoneyJson.plain(new BigDecimal("10.001")), "more decimals are kept, not rounded");
    }
}
