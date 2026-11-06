package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.exception.PinUnavailableException;
import org.elibanking.transactionservice.security.Caller;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fail closed (wallet-plan 8.22, 8.32). While the PIN is required, a PIN that cannot be checked refuses the transfer with 503,
 * and a transfer with no PIN is refused with 400. With the requirement off, nothing is checked. Plain objects, no context.
 */
class PinFailsClosedTest {

    private static final Caller OWNER = new Caller("owner-1", null, "USER");

    @Test
    void anUnavailableCheckRefusesWhileTheRequirementIsOn() {
        PinGate gate = new PinGate(unavailable(), true);

        assertThrows(PinUnavailableException.class, () -> gate.check(OWNER, "1234"));
    }

    @Test
    void aTransferWithoutAPinIsRefusedWhileTheRequirementIsOn() {
        PinGate gate = new PinGate(unavailable(), true);

        assertThrows(IllegalArgumentException.class, () -> gate.check(OWNER, null));
        assertThrows(IllegalArgumentException.class, () -> gate.check(OWNER, "  "));
    }

    @Test
    void nothingIsCheckedWhileTheRequirementIsOff() {
        AtomicInteger calls = new AtomicInteger();
        PinGate gate = new PinGate((userId, pin) -> calls.incrementAndGet(), false);

        assertDoesNotThrow(() -> gate.check(OWNER, null));
        assertDoesNotThrow(() -> gate.check(OWNER, "1234"));
        assertEquals(0, calls.get());
    }

    private static PinVerifier unavailable() {
        return (userId, pin) -> {
            throw new PinUnavailableException();
        };
    }
}
