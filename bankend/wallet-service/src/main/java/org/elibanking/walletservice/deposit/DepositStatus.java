package org.elibanking.walletservice.deposit;

/** Where a card deposit stands (wallet-plan 3.5). Only PENDING exists until slice 5.4 consumes the Stripe event. */
public enum DepositStatus {
    PENDING,
    COMPLETED,
    FAILED,
    EXPIRED
}
