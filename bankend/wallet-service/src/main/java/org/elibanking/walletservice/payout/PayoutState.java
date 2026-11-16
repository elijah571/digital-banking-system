package org.elibanking.walletservice.payout;

/** Where an outbound payout (a withdrawal or an external transfer) stands (wallet-plan 8.3). */
public enum PayoutState {
    REQUESTED,
    PENDING_APPROVAL,
    APPROVED,
    PENDING_PROVIDER,
    PROCESSING,
    AMBIGUOUS,
    COMPLETED,
    FAILED,
    REQUIRES_MANUAL_REVIEW,
    REJECTED,
    CANCELLED,
    EXPIRED,
    REVERSED
}
