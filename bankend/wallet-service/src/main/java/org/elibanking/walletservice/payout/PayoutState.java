package org.elibanking.walletservice.payout;

/** Where an outbound payout (a withdrawal or an external transfer) stands (wallet-plan 8.3). */
public enum PayoutState {
    REQUESTED,
    PENDING_APPROVAL,
    APPROVED,
    PROCESSING,
    COMPLETED,
    FAILED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    REVERSED
}
