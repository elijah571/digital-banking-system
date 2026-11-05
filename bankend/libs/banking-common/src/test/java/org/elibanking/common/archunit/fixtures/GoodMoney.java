package org.elibanking.common.archunit.fixtures;

import java.math.BigDecimal;
import java.util.Objects;

/** Follows every money rule; used by MoneyArchRulesTest. */
public class GoodMoney {
    private BigDecimal amount;
    private int count;

    public boolean same(BigDecimal other) {
        return amount.compareTo(other) == 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GoodMoney g && amount.equals(g.amount) && count == g.count;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, count);
    }
}
