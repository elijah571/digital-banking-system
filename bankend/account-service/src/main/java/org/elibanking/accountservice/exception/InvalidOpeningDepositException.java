package org.elibanking.accountservice.exception;

/**
 * An opening deposit that is refused: a non-zero amount while ACCOUNT_OPENING_DEPOSIT_ENABLED is off, or an amount that is
 * negative or has more than two decimals (wallet-plan 3.4, D4). Mapped to 400.
 */
public class InvalidOpeningDepositException extends RuntimeException {

    public InvalidOpeningDepositException(String message) {
        super(message);
    }
}
