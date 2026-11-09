package org.elibanking.transactionservice.service;

public class WalletUnavailableException extends RuntimeException {
    public WalletUnavailableException() {
        super("Wallet service is unavailable, please retry");
    }
}
