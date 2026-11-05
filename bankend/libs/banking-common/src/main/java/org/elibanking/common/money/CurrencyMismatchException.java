package org.elibanking.common.money;

/** Arithmetic or comparison between two different currencies. Maps to HTTP 422. */
public class CurrencyMismatchException extends IllegalArgumentException {
    public CurrencyMismatchException(String expected, String actual) {
        super("Currency mismatch: " + expected + " vs " + actual);
    }
}
