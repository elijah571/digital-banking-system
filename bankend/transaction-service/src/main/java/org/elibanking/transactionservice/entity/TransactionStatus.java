package org.elibanking.transactionservice.entity;

public enum TransactionStatus {
    PROCESSING,
    PENDING,
    COMPLETED,
    PENDING_VERIFICATION,
    FLAGGED,
    FAILED,
    /** A transfer that waited for its code past the code's lifetime, and was released (wallet-plan 8.34). */
    EXPIRED

}
