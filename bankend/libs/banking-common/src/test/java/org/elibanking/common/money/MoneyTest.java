package org.elibanking.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesToCurrencyScale() {
        assertThat(Money.of("1500", "USD").toPlainString()).isEqualTo("1500.00");
        assertThat(Money.of("10.500", "USD").toPlainString()).isEqualTo("10.50");
        assertThat(Money.of("100", "JPY").toPlainString()).isEqualTo("100");
        assertThat(Money.of("1500.1", "USD").toStorage().toPlainString()).isEqualTo("1500.1000");
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyAllows_neverRounds() {
        assertThatThrownBy(() -> Money.of("10.505", "USD")).isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("2 decimal places");
        assertThatThrownBy(() -> Money.of("0.001", "NGN")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.of("1.5", "JPY")).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void positiveRejectsZeroAndNegative() {
        assertThatThrownBy(() -> Money.positive("0", "USD")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.positive("-1", "USD")).isInstanceOf(InvalidAmountException.class);
        assertThat(Money.positive("0.01", "USD").signum()).isEqualTo(1);
    }

    @Test
    void rejectsGarbageAndUnknownCurrencies() {
        assertThatThrownBy(() -> Money.of("abc", "USD")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.of(" ", "USD")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.of("1", "ZZZ")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.of("1", "XXX")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.of("1e30", "USD")).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void arithmeticAndComparisonRequireSameCurrency() {
        Money a = Money.of("10.00", "USD");
        assertThat(a.add(Money.of("0.05", "USD"))).isEqualTo(Money.of("10.05", "USD"));
        assertThat(a.subtract(Money.of("10.01", "USD")).signum()).isEqualTo(-1);
        assertThat(a.compareTo(Money.of(new BigDecimal("10.0"), "USD"))).isZero();
        assertThat(a).isEqualTo(Money.of(new BigDecimal("10.0000"), "USD"));  // scale normalised
        assertThatThrownBy(() -> a.add(Money.of("1", "NGN"))).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> a.compareTo(Money.of("1", "NGN"))).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void multiplyRoundsHalfEven() {
        assertThat(Money.of("0.25", "USD").multiply(new BigDecimal("0.5")).toPlainString()).isEqualTo("0.12");
        assertThat(Money.of("0.35", "USD").multiply(new BigDecimal("0.5")).toPlainString()).isEqualTo("0.18");
    }

    @Test
    void minorUnits() {
        assertThat(Money.of("15.99", "USD").toMinorUnits()).isEqualTo(1599);
        assertThat(Money.of("100", "JPY").toMinorUnits()).isEqualTo(100);
    }
}
