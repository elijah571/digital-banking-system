package org.elibanking.paymentservice.payout;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** One send, with the outcome its account number chose, and how many times its status has been asked. */
    private static final class Sent {
        final SendResult result;
        final char lastDigit;
        final AtomicInteger statusChecks = new AtomicInteger();

        Sent(SendResult result, char lastDigit) {
            this.result = result;
            this.lastDigit = lastDigit;
        }
    }

    /** The sends, by key. A send with a key already here returns its first answer (8.7). */
    private final Map<String, Sent> sends = new ConcurrentHashMap<>();

    /**
     * The outcome comes from the last digit of the account number, so tests are deterministic (8.7): 0 is a send that FAILS; 1 is PENDING
     * until its status is asked, then SUCCESS; 2 is SUCCESS, then REVERSED at the next status check; anything else is ACCEPTED and then SUCCESS.
     */
    @Override
    public SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName, String narration) {
        char last = accountNumber.charAt(accountNumber.length() - 1);
        Sent sent = sends.computeIfAbsent(key, k -> new Sent(new SendResult("SIM-" + k, sendStatusFor(last)), last));
        return sent.result;
    }

    @Override
    public PayoutStatus getStatus(String key) {
        Sent sent = sends.get(key);
        if (sent == null) {
            return PayoutStatus.NOT_FOUND;
        }
        int check = sent.statusChecks.incrementAndGet();
        return switch (sent.lastDigit) {
            case '0' -> PayoutStatus.FAILED;
            case '2' -> check == 1 ? PayoutStatus.SUCCESS : PayoutStatus.REVERSED;
            default -> PayoutStatus.SUCCESS;
        };
    }

    private static SendStatus sendStatusFor(char last) {
        return switch (last) {
            case '0' -> SendStatus.FAILED;
            case '1' -> SendStatus.PENDING;
            default -> SendStatus.ACCEPTED;
        };
    }
}
