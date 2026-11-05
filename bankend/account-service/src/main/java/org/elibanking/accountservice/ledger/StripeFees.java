package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Converts a Stripe balance transaction's fee into the ledger currency (D27).
 *
 * <p>Stripe reports {@code exchange_rate} as units of the settlement currency per unit of the presentment currency
 * (for NGN → USD, about 0.00071). So a fee in settlement units becomes NGN by dividing by that rate. The result is
 * rounded HALF_EVEN to 2 decimals. When the fee is already in NGN, nothing is converted.
 */
public final class StripeFees {

    private static final String LEDGER_CURRENCY = "NGN";
    private static final MathContext DIVISION = new MathContext(20, RoundingMode.HALF_EVEN);

    private StripeFees() {
    }

    /**
     * @param fee                the fee as Stripe reports it, in the settlement currency
     * @param settlementCurrency the settlement currency code, e.g. {@code usd}
     * @param exchangeRate       settlement units per NGN, or {@code null} when no conversion happened
     */
    public static Money toNgn(BigDecimal fee, String settlementCurrency, BigDecimal exchangeRate) {
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(settlementCurrency, "settlementCurrency");
        if (fee.signum() < 0) {
            throw new InvalidPostingException("fee cannot be negative");
        }
        if (LEDGER_CURRENCY.equalsIgnoreCase(settlementCurrency)) {
            return Money.of(fee.setScale(2, RoundingMode.HALF_EVEN), LEDGER_CURRENCY);
        }
        if (exchangeRate == null || exchangeRate.signum() <= 0) {
            throw new InvalidPostingException("exchange rate is required to convert a " + settlementCurrency + " fee");
        }
        BigDecimal ngn = fee.divide(exchangeRate, DIVISION).setScale(2, RoundingMode.HALF_EVEN);
        return Money.of(ngn, LEDGER_CURRENCY);
    }
}
