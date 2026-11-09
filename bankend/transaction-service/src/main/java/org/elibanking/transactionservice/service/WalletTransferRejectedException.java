package org.elibanking.transactionservice.service;

public class WalletTransferRejectedException extends RuntimeException {
    private final int status;

    public WalletTransferRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
