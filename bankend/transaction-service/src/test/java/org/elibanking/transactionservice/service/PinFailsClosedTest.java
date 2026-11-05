package org.elibanking.transactionservice.service;

import org.elibanking.transactionservice.security.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The 3c.7 default (wallet-plan 8.32): until wallet-service's PIN check is wired in, a transfer with the requirement on is
 * refused with 503, and nothing is checked or moved. Plain objects, no Spring context.
 */
class PinFailsClosedTest {

    private static final Caller OWNER = new Caller("owner-1", null, "USER");

    @Test
    void theDefaultVerifierRefusesWhileTheRequirementIsOn() {
        PinGate gate = new PinGate(new UnavailablePinVerifier(), true);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> gate.check(OWNER, "1234"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), refused.getStatusCode().value());
    }

    @Test
    void aTransferWithoutAPinIsRefusedWhileTheRequirementIsOn() {
        PinGate gate = new PinGate(new UnavailablePinVerifier(), true);

        assertThrows(IllegalArgumentException.class, () -> gate.check(OWNER, null));
        assertThrows(IllegalArgumentException.class, () -> gate.check(OWNER, "  "));
    }

    @Test
    void nothingIsCheckedWhileTheRequirementIsOff() {
        PinGate gate = new PinGate(new UnavailablePinVerifier(), false);

        assertDoesNotThrow(() -> gate.check(OWNER, null));
        assertDoesNotThrow(() -> gate.check(OWNER, "1234"));
    }
}
