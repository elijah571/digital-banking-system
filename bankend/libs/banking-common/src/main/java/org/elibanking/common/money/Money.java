package org.elibanking.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact amount of one currency.
 *
 * <p>Rules (see docs/ledger.md):
 * <ul>
 *   <li>{@link BigDecimal} only, always held at the currency's scale (USD: 2, JPY: 0).</li>
 *   <li>Input is never rounded: an amount with more significant decimals than the currency allows is rejected
 *       ({@code 10.505 USD} → error; {@code 10.500 USD} is exactly 10.50, so it is accepted).</li>
 *   <li>Compare with {@link #compareTo}; {@link #equals} is safe too, because the scale is normalised.</li>
 *   <li>Derived amounts (e.g. a percentage) round with {@link #ROUNDING} = HALF_EVEN.</li>
 *   <li>The database stores {@code DECIMAL(19,4)}: use {@link #toStorage()}.</li>
 * </ul>
 * Zero and negative values are allowed (balances); use {@link #positive} for user-entered amounts.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    /** Scale of every money column in MySQL ({@code DECIMAL(19,4)}). */
    public static final int STORAGE_SCALE = 4;
    /** Largest absolute value that fits {@code DECIMAL(19,4)}. */
    private static final BigDecimal MAX_ABS = new BigDecimal("999999999999999.9999");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int scale = scaleOf(currency);
        if (amount.stripTrailingZeros().scale() > scale) {
            throw new InvalidAmountException("Amount " + amount.toPlainString() + " has more than " + scale
                    + " decimal places allowed for " + currency.getCurrencyCode());
        }
        if (amount.abs().compareTo(MAX_ABS) > 0) {
            throw new InvalidAmountException("Amount is too large");
        }
        amount = amount.setScale(scale, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currency(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return of(parse(amount), currencyCode);
    }

    public static Money zero(String currencyCode) {
        return of(BigDecimal.ZERO, currencyCode);
    }

    /** A user-entered amount: must be &gt; 0 and fit the currency's decimals. */
    public static Money positive(BigDecimal amount, String currencyCode) {
        Money money = of(amount, currencyCode);
        if (money.signum() <= 0) {
            throw new InvalidAmountException("Amount must be greater than 0");
        }
        return money;
    }

    public static Money positive(String amount, String currencyCode) {
        return positive(parse(amount), currencyCode);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** {@code this × factor}, rounded HALF_EVEN to the currency's scale (for fees, percentages…). */
    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor).setScale(scaleOf(currency), ROUNDING), currency);
    }

    public int signum() {
        return amount.signum();
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    /** Value for a {@code DECIMAL(19,4)} column. */
    public BigDecimal toStorage() {
        return amount.setScale(STORAGE_SCALE, RoundingMode.UNNECESSARY);
    }

    /** Amount in the currency's smallest unit (cents), e.g. for Stripe. */
    public long toMinorUnits() {
        return amount.movePointRight(scaleOf(currency)).longValueExact();
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    /** JSON form, e.g. {@code "1500.00"} (amounts travel as strings). */
    public String toPlainString() {
        return amount.toPlainString();
    }

    /** Log / display form, e.g. {@code "1500.00 USD"}. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency.getCurrencyCode(), other.currency.getCurrencyCode());
        }
    }

    private static int scaleOf(Currency currency) {
        // Pseudo-currencies (XXX, XAU…) report -1; there is no sensible scale for them.
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new InvalidAmountException("Unsupported currency " + currency.getCurrencyCode());
        }
        return digits;
    }

    private static Currency currency(String code) {
        try {
            return Currency.getInstance(Objects.requireNonNull(code, "currency"));
        } catch (IllegalArgumentException e) {
            throw new InvalidAmountException("Unknown currency " + code);
        }
    }

    private static BigDecimal parse(String amount) {
        if (amount == null || amount.isBlank()) {
            throw new InvalidAmountException("Amount is required");
        }
        try {
            return new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            throw new InvalidAmountException("Amount is not a number: " + amount);
        }
    }
}
