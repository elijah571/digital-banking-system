package org.elibanking.paymentservice.payout;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The simulated provider (wallet-plan 8.7), for dev, tests and CI. The bank list is fixed. A ten-digit account number is found, and
 * its name is derived from the number, except that a number ending in 0 is not found and one ending in 9 is unavailable. A bank that
 * is not in the list is not found. Payout outcomes are deterministic for account numbers ending in 0, 1 or 2; other numbers use the
 * configured default.
 */
public class SimulatedPayoutProvider implements PayoutProvider {

    public enum DefaultOutcome {
        SUCCESS,
        FAILURE,
        PENDING;

        static DefaultOutcome parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("PAYOUT_SIM_DEFAULT_OUTCOME must be success, failure or pending", invalid);
            }
        }
    }

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
    private final DefaultOutcome defaultOutcome;
    private final long delayMs;

    public SimulatedPayoutProvider(DefaultOutcome defaultOutcome, long delayMs) {
        this.defaultOutcome = Objects.requireNonNull(defaultOutcome);
        if (delayMs < 0) {
            throw new IllegalArgumentException("PAYOUT_SIM_DELAY_MS must not be negative");
        }
        this.delayMs = delayMs;
    }

    /**
     * The outcome comes from the last digit of the account number, so tests are deterministic (8.7): 0 is a send that FAILS; 1 is PENDING
     * until its status is asked, then SUCCESS; 2 is SUCCESS, then REVERSED at the next status check. Other numbers use the configured
     * default outcome.
     */
    @Override
    public SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName, String narration) {
        pause();
        char last = accountNumber.charAt(accountNumber.length() - 1);
        Sent sent = sends.computeIfAbsent(key, k -> new Sent(new SendResult("SIM-" + k, sendStatusFor(last)), last));
        return sent.result;
    }

    @Override
    public PayoutStatus getStatus(String key) {
        pause();
        Sent sent = sends.get(key);
        if (sent == null) {
            return PayoutStatus.NOT_FOUND;
        }
        int check = sent.statusChecks.incrementAndGet();
        return switch (sent.lastDigit) {
            case '0' -> PayoutStatus.FAILED;
            case '2' -> check == 1 ? PayoutStatus.SUCCESS : PayoutStatus.REVERSED;
            case '1' -> PayoutStatus.SUCCESS;
            default -> switch (defaultOutcome) {
                case SUCCESS -> PayoutStatus.SUCCESS;
                case FAILURE -> PayoutStatus.FAILED;
                case PENDING -> PayoutStatus.PENDING;
            };
        };
    }

    private SendStatus sendStatusFor(char last) {
        return switch (last) {
            case '0' -> SendStatus.FAILED;
            case '1' -> SendStatus.PENDING;
            case '2' -> SendStatus.ACCEPTED;
            default -> switch (defaultOutcome) {
                case SUCCESS -> SendStatus.ACCEPTED;
                case FAILURE -> SendStatus.FAILED;
                case PENDING -> SendStatus.PENDING;
            };
        };
    }

    private void pause() {
        if (delayMs == 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("simulated payout was interrupted", interrupted);
        }
    }
}
