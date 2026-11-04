package org.elibanking.accountservice.ledger;

/** One side of a journal line. Every entry must have equal debits and credits per currency. */
public enum Side {
    DEBIT,
    CREDIT;

    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
