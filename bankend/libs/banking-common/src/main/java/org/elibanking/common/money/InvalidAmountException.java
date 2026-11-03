package org.elibanking.common.money;

/** The amount is not valid for its currency (too many decimals, not positive, unknown currency…). Maps to HTTP 400. */
public class InvalidAmountException extends IllegalArgumentException {
    public InvalidAmountException(String message) {
        super(message);
    }
}
