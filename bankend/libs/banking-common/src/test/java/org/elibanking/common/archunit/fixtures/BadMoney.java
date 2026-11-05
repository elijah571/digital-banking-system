package org.elibanking.common.archunit.fixtures;

import java.math.BigDecimal;

/** Deliberately breaks every money rule; used by MoneyArchRulesTest. */
public class BadMoney {
    private BigDecimal amount;
    private double rate;

    public double asDouble() {
        return amount.doubleValue();
    }

    public BigDecimal fromDouble(double d) {
        return BigDecimal.valueOf(d);
    }

    public boolean same(BigDecimal other) {
        return amount.equals(other);
    }
}
