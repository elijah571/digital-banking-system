package org.elibanking.common.money;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Randomized money arithmetic checks; jqwik runs each property for 2,000 generated values. */
class MoneyPropertyTest {

    @Property(tries = 2_000)
    void addingThenSubtractingAnAmountPreservesTheStartingBalance(
            @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long startingCents,
            @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long deltaCents) {
        Money start = Money.of(BigDecimal.valueOf(startingCents, 2), "NGN");
        Money delta = Money.of(BigDecimal.valueOf(deltaCents, 2), "NGN");
        Money updated = start.add(delta);

        assertEquals(start, updated.subtract(delta));
        assertEquals(4, updated.toStorage().scale());
        assertTrue(updated.compareTo(start) >= 0);
    }
}
