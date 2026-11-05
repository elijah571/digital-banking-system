package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DepositSettlementTest {

    @Test
    void exactAmountIsCompletedWithNothingInSuspense() {
        DepositSettlement s = settlement("1000.00", "1000.00");

        assertEquals(DepositSettlement.Outcome.COMPLETED, s.outcome());
        assertEquals("1000.00 NGN", s.credit().toString());
        assertEquals("0.00 NGN", s.suspense().toString());
    }

    @Test
    void surplusCreditsTheRequestAndSuspensesTheDifference() {
        DepositSettlement s = settlement("1000.00", "1050.00");

        assertEquals(DepositSettlement.Outcome.COMPLETED, s.outcome());
        assertEquals("1000.00 NGN", s.credit().toString());
        assertEquals("50.00 NGN", s.suspense().toString());
    }

    @Test
    void shortfallIsFailedWithNothingCreditedAndEverythingInSuspense() {
        DepositSettlement s = settlement("1000.00", "600.00");

        assertEquals(DepositSettlement.Outcome.FAILED, s.outcome());
        assertEquals("0.00 NGN", s.credit().toString());
        assertEquals("600.00 NGN", s.suspense().toString());
    }

    @Test
    void nothingArrivingIsFailedAndLeavesSuspenseEmpty() {
        DepositSettlement s = settlement("1000.00", "0.00");

        assertEquals(DepositSettlement.Outcome.FAILED, s.outcome());
        assertEquals("0.00 NGN", s.suspense().toString());
    }

    @Test
    void aReceiptInAnotherCurrencyIsRejected() {
        assertThrows(InvalidPostingException.class,
                () -> new DepositSettlement(Money.of("1000.00", "NGN"), Money.of("1000.00", "USD")));
    }

    private static DepositSettlement settlement(String requested, String received) {
        return new DepositSettlement(Money.of(requested, "NGN"), Money.of(received, "NGN"));
    }
}
