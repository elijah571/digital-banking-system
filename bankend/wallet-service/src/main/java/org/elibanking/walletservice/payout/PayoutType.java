package org.elibanking.walletservice.payout;

/** The two kinds of outbound payout. They share one pipeline and one table (wallet-plan 8.3). */
public enum PayoutType {
    WITHDRAWAL,
    EXTERNAL_TRANSFER
}
