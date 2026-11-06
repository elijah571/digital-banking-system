package org.elibanking.paymentservice.payout;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The simulated provider (wallet-plan 8.7), for dev, tests and CI. The bank list is fixed. A ten-digit account number is found, and
 * its name is derived from the number, except that a number ending in 0 is not found and one ending in 9 is unavailable. A bank that
 * is not in the list is not found. Paystack's test mode cannot exercise these failures, so the simulator is where they are tested.
 */
@Component
public class SimulatedPayoutProvider implements PayoutProvider {

    private static final List<Bank> BANKS = List.of(
            new Bank("Simulated Bank Alpha", "001"),
            new Bank("Simulated Bank Beta", "002"));

    @Override
    public List<Bank> listBanks() {
        return BANKS;
    }

    @Override
    public Resolution resolveAccount(String bankCode, String accountNumber) {
        boolean knownBank = BANKS.stream().anyMatch(bank -> bank.code().equals(bankCode));
        if (!knownBank || !accountNumber.matches("\\d{10}")) {
            return new Resolution(Outcome.NOT_FOUND, null);
        }
        char last = accountNumber.charAt(9);
        if (last == '0') {
            return new Resolution(Outcome.NOT_FOUND, null);
        }
        if (last == '9') {
            return new Resolution(Outcome.UNAVAILABLE, null);
        }
        return new Resolution(Outcome.FOUND, "SIMULATED ACCOUNT " + accountNumber.substring(6));
    }
}
