package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StripeFeesTest {

    @Test
    void ngnFeeNeedsNoConversion() {
        Money fee = StripeFees.toNgn(new BigDecimal("12.5"), "ngn", null);

        assertEquals("12.50 NGN", fee.toString());
    }

    @Test
    void usdFeeConvertsAtTheBalanceTransactionRate() {
        // The G1 sample: a 33-cent fee at 0.000710374 USD per NGN is 464.54 NGN.
        Money fee = StripeFees.toNgn(new BigDecimal("0.33"), "usd", new BigDecimal("0.000710374"));

        assertEquals("464.54 NGN", fee.toString());
    }

    @Test
    void ties_roundHalfEvenToTwoDecimals() {
        // Rate 1 makes the fee itself the NGN amount, so these are exact ties at the third decimal.
        assertEquals("0.02 NGN", StripeFees.toNgn(new BigDecimal("0.015"), "usd", BigDecimal.ONE).toString());
        assertEquals("0.02 NGN", StripeFees.toNgn(new BigDecimal("0.025"), "usd", BigDecimal.ONE).toString());
        assertEquals("0.04 NGN", StripeFees.toNgn(new BigDecimal("0.035"), "usd", BigDecimal.ONE).toString());
    }

    @Test
    void aForeignFeeWithoutARateIsRejected() {
        assertThrows(InvalidPostingException.class,
                () -> StripeFees.toNgn(new BigDecimal("0.33"), "usd", null));
    }

    @Test
    void aNegativeFeeIsRejected() {
        assertThrows(InvalidPostingException.class,
                () -> StripeFees.toNgn(new BigDecimal("-0.33"), "usd", new BigDecimal("0.000710374")));
    }
}
