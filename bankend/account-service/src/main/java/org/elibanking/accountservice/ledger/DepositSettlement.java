package org.elibanking.accountservice.ledger;

import org.elibanking.common.money.Money;

import java.util.Objects;

/**
 * How one card deposit settles, given what the wallet asked for and what the provider actually received (D26).
 *
 * <ul>
 *   <li>The wallet is credited exactly the requested amount, never more and never less.</li>
 *   <li>Every received amount that is not credited goes to {@code DEPOSIT_SUSPENSE}: a surplus, or the whole
 *       received amount on a shortfall.</li>
 *   <li>A shortfall (received &lt; requested) is FAILED for the user, but what arrived still goes to suspense.</li>
 * </ul>
 */
public record DepositSettlement(Money requested, Money received) {

    public enum Outcome {
        COMPLETED,
        FAILED
    }

    public DepositSettlement {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(received, "received");
        if (!requested.currencyCode().equals(received.currencyCode())) {
            throw new InvalidPostingException("received " + received.currencyCode()
                    + " does not match requested " + requested.currencyCode());
        }
        if (requested.signum() <= 0) {
            throw new InvalidPostingException("requested amount must be greater than 0");
        }
        if (received.signum() < 0) {
            throw new InvalidPostingException("received amount cannot be negative");
        }
    }

    public Outcome outcome() {
        return received.compareTo(requested) >= 0 ? Outcome.COMPLETED : Outcome.FAILED;
    }

    /** The amount credited to the wallet: the requested amount if fully received, otherwise nothing. */
    public Money credit() {
        return outcome() == Outcome.COMPLETED ? requested : Money.zero(requested.currencyCode());
    }

    /** Everything received that is not credited: the surplus, or the whole amount on a shortfall. */
    public Money suspense() {
        return received.subtract(credit());
    }
}
