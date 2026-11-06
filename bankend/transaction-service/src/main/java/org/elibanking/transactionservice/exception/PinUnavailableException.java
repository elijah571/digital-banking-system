package org.elibanking.transactionservice.exception;

/** The PIN could not be checked because wallet-service did not answer. The transfer is refused (503, fail closed). */
public class PinUnavailableException extends RuntimeException {

    public PinUnavailableException() {
        super("Transfers that need a PIN are unavailable right now. Try again shortly.");
    }
}
