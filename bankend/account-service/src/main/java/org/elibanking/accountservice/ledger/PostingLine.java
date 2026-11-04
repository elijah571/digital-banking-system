package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;

import java.util.Objects;

/** One line of a journal entry: a side, an amount in one currency, and the ledger account it touches. */
public record PostingLine(long ledgerAccountId, Side side, Money amount) {

    public PostingLine {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
    }
}
